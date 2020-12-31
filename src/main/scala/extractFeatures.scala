import utils.{hourOfDay, transTimeToTimestamp, dayOfMonth_string, secondsOfDay, periodOfDay, hourOfDay_Long}
import org.apache.spark.sql.SparkSession
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.math.{Pi, abs, exp, min, pow, sqrt}


object extractFeatures {
    def main(args: Array[String]): Unit = {

        val spark = SparkSession.builder().appName("extractFeatures").getOrCreate()
        val sc = spark.sparkContext

        case class distAndKinds(var d:Long, var k:Int)

        val stationInfo = sc.textFile(args(0)).map(line => {
            val fields = line.split(",")
            val num = fields.head.toInt - 1
            val name = fields(1)
            (name, num)
        })
        val stationNumMap = sc.broadcast(stationInfo.collect().toMap)

        val odPair = sc.textFile(args(1)).map(line => {
            val fields = line.split(',')
            val ot = hourOfDay(fields(1))
            val dt = hourOfDay(fields(4))
            val os = stationNumMap.value(fields(2))
            val ds = stationNumMap.value(fields(5))
            ((os, ds), ot, dt)
        }).cache()

        val allDayFlow = odPair.map(line => (line._1, 1))
            .reduceByKey(_+_)
            .map(x => (x._1, x._2 ))

        val allDayFlowMap = allDayFlow.collect().toMap
        val allDayFlowMatrix = Array.ofDim[Float](166, 166)
        for (k <- allDayFlowMap.keys){
            allDayFlowMatrix(k._1)(k._2) = allDayFlowMap(k)
        }
        for (i <- 0.until(166)){
            val s = allDayFlowMatrix(i).sum
            for (j <- 0.until(166)){
                allDayFlowMatrix(i)(j) = (allDayFlowMatrix(i)(j) / s).formatted("%.5f").toFloat
            }
        }

        val periodFlow = odPair.map(line => (line._2, line._1))
            .groupByKey()
            .mapValues(data=>{
                val count = data.groupBy(x => x).mapValues(_.size)
                val matrix = Array.ofDim[Float](166,166)
                for ( p <- count.keys) {
                    matrix(p._1)(p._2) = count(p)
                }

                for (i <- 0.until(166)){
                    val s = matrix(i).sum
                    for (j <- 0.until(166)){
                        matrix(i)(j) = (matrix(i)(j) / s).formatted("%.5f").toFloat
                    }
                }
                matrix
            })

        val periodFlowMap = sc.broadcast(periodFlow.collect().toMap)

        val odPairWithID = sc.textFile(args(1)).map(line => {
            val fields = line.split(',')
            val id = fields(0).drop(1)
            val ot = transTimeToTimestamp(fields(1))
            val dt = transTimeToTimestamp(fields(4))
            val os = stationNumMap.value(fields(2))
            val ds = stationNumMap.value(fields(5))
            val o_day = dayOfMonth_string(fields(1))
            val d_day = dayOfMonth_string(fields(4))
            val day = if (o_day == d_day) o_day else 0
            (id, (ot, os, dt, ds, day))
        })

        val groupByID = odPairWithID.groupByKey()
            .map(line => {
                val dataArray = line._2.toList.sortBy(_._1)
                val daySets = dataArray.map(_._5).toSet
                (line._1, dataArray, daySets)
            }).filter(x => x._3.size > 5)

        val irregularTrip = groupByID.map(line => {
            var feature_g_day = Array.emptyFloatArray
            var feature_g_period = Array.emptyFloatArray
            val feature_i_1 = Array.ofDim[Int](166)
            val feature_i_2 = Array.ofDim[Int](166)
            val pairs = line._2
            val daySets = line._3
            val stationsNum = Array.ofDim[Int](166)

            val stampBuffer = new ArrayBuffer[Long]()
            pairs.foreach(v => {
                stampBuffer.append(secondsOfDay(v._1))
                stampBuffer.append(secondsOfDay(v._3))
            })
            val timestamps = stampBuffer.toArray.sorted

            val h = 1800
            val density_stamp_Buffer = new ArrayBuffer[(Double, Long)]()
            for (t <- timestamps) {
                var temp = 0D
                for (v <- timestamps) {
                    temp += RBF(v, t, h)
                }
                density_stamp_Buffer.append((temp / (timestamps.length * h), t))
            }
            val density_stamp = density_stamp_Buffer.toArray.sortBy(_._2)
            val cluster_center = z_score(density_stamp)

            var tripIndex = -1
            var havePattern = 0
            if (cluster_center.nonEmpty){
                val dc = 5400
                val clusters = new ArrayBuffer[(Int, (Long, Int, Long, Int, Int))]
                for (v <- pairs) {
                    val o_stamp = secondsOfDay(v._1)
                    val d_stamp = secondsOfDay(v._3)
                    val o_to_c = distAndKinds(Long.MaxValue, 0)
                    val d_to_c = distAndKinds(Long.MaxValue, 0)
                    for (c <- cluster_center) {
                        if (abs(o_stamp - c._2) < dc && abs(o_stamp - c._2) < o_to_c.d){
                            o_to_c.k = c._1
                            o_to_c.d = abs(o_stamp - c._2)
                        }
                        if (abs(d_stamp - c._2) < dc && abs(d_stamp - c._2) < d_to_c.d){
                            d_to_c.k = c._1
                            d_to_c.d = abs(d_stamp - c._2)
                        }
                    }
                    if (o_to_c.k == d_to_c.k && o_to_c.k != 0)
                        clusters.append(( o_to_c.k, v))
                    else
                        clusters.append((0, v))
                }

                val afc_patterns = new ArrayBuffer[(Int, Int)]()
                val groupByPair = pairs.groupBy(x => (x._2, x._4)).mapValues(_.size)
                for (k <- groupByPair.keys) {
                    if (groupByPair(k) + groupByPair.getOrElse((k._2, k._1), 0) > pairs.length / 2) {
                        afc_patterns.append(k)
                    }
                }

                val grouped = clusters.toArray.filter(x => x._1 > 0).groupBy(_._1)
                if (grouped.nonEmpty){
                    grouped.foreach(g => {
                        val temp_data = g._2.groupBy(x => (x._2._2, x._2._4))
                        temp_data.foreach(v => {
                            if ( v._2.length >= 5 || v._2.length > daySets.size / 2) {
                                afc_patterns.append(v._1)
                            }
                        })
                    })
                }

                if (afc_patterns.isEmpty) {
                    tripIndex = pairs.length - 1
                } else {
                    val len = pairs.length - 1
                    var flag = true
                    for (i <- len.to(0, -1) if flag) {
                        val tr = (pairs(i)._2, pairs(i)._4)
                        if (!afc_patterns.contains(tr)) {
                            tripIndex = i
                            havePattern = 1
                            flag = false
                        }
                    }
                }
            }
            else{
                tripIndex = pairs.length - 1
            }


            if (tripIndex > 0) {
                val hisTrips = pairs.take(tripIndex)
                val trip = pairs(tripIndex)

                val fixOt = hisTrips.map(x => (periodOfDay(x._1), x._4)).groupBy(x => x).mapValues(_.size)
                    .map(x => (x._1._1, (x._1._2, x._2)))
                    .groupBy(x => x._1)
                    .mapValues(v => v.values.toList)

                val fixOs = hisTrips.map(x => (x._2, x._4)).groupBy(x => x).mapValues(_.size)
                    .map(x => (x._1._1, (x._1._2, x._2)))
                    .groupBy(x => x._1)
                    .mapValues(v => v.values.toList)

                hisTrips.foreach(x => {
                    stationsNum(x._2) += 1
                    stationsNum(x._4) += 1
                })

                feature_g_day = allDayFlowMatrix(trip._2)
                feature_g_period = periodFlowMap.value(hourOfDay_Long(trip._1))(trip._2)
                val p = periodOfDay(trip._1)
                if (fixOt.contains(p)) {
                    for (v <- fixOt(p))
                        feature_i_1(v._1) = v._2
                }
                if (fixOs.contains(trip._2)) {
                    for (v <- fixOs(trip._2))
                        feature_i_2(v._1) = v._2
                }
                val target = trip._4
                val st = hourOfDay_Long(trip._1) / 2

                trip._2.toString + ":" + feature_g_day.mkString(",") + "#" + feature_g_period.mkString(",") + "#" +
                    feature_i_1.mkString(",") + "#" + feature_i_2.mkString(",") + "#" + stationsNum.mkString(",") +
                    ":" + target.toString + ":" + st.toString + ":" + tripIndex.toString + ":" + havePattern.toString
            }
            else
                ""
        }).filter(x => x.nonEmpty)

        irregularTrip.repartition(5).saveAsTextFile(args(2))
        sc.stop()
    }


    def RBF(l : Long, x : Long, h: Int) : Double = {
        1 / sqrt(2 * Pi) * exp(-pow(x - l, 2) / (2 * pow(h, 2)))
    }

    def z_score(dens_pos : Array[(Double, Long)]) : Array[(Int, Long)] = {
        val dist_r = compute_dist(dens_pos)
        val dist_l = compute_dist(dens_pos.reverse).reverse
        val dist_dens_pos = new ArrayBuffer[(Long, Double, Long)]()
        for (i <- dist_r.indices) {
            if (dist_r(i) == -1 && dist_l(i) == -1)
                dist_dens_pos.append((dens_pos.last._2 - dens_pos.head._2, dens_pos(i)._1, dens_pos(i)._2))
            else if (dist_r(i) != -1 && dist_l(i) != -1)
                dist_dens_pos.append((min(dist_r(i), dist_l(i)), dens_pos(i)._1, dens_pos(i)._2))
            else if (dist_l(i) != -1)
                dist_dens_pos.append((dist_l(i), dens_pos(i)._1, dens_pos(i)._2))
            else
                dist_dens_pos.append((dist_r(i), dens_pos(i)._1, dens_pos(i)._2))
        }
        var sum_dist = 0L
        var sum_dens = 0d
        dist_dens_pos.foreach(x => {
            sum_dist += x._1
            sum_dens += x._2
        })
        val avg_dist = sum_dist / dist_dens_pos.length
        val avg_dens = sum_dens / dist_dens_pos.length
        var total = 0d
        for (v <- dist_dens_pos) {
            total += pow(abs(v._1 - avg_dist), 2) + pow(abs(v._2 - avg_dens), 2)
        }
        val sd = sqrt(total / dist_dens_pos.length)
        val z_score = new ArrayBuffer[((Long, Double, Long), Double)]()
        var z_value = 0d
        for (v <- dist_dens_pos) {
            z_value = sqrt(pow(abs(v._1 - avg_dist), 2) + pow(abs(v._2 - avg_dens), 2)) / sd
            z_score.append((v, z_value))
        }
        val result = new ArrayBuffer[(Int, Long)]()
        val clustersInfo = z_score.toArray.filter(_._2 >= 3)
        for (i <- clustersInfo.indices) {
            result.append((i+1, clustersInfo(i)._1._3))
        }
        result.toArray
    }

    def compute_dist(info : Array[(Double, Long)]) : Array[Long] = {
        val result = new Array[Long](info.length)
        val s = mutable.Stack[Int]()
        s.push(0)
        var i = 1
        var index = 0
        while (i < info.length) {
            if (s.nonEmpty && info(i)._1 > info(s.top)._1) {
                index = s.pop()
                result(index) = abs(info(i)._2 - info(index)._2)
            }
            else{
                s.push(i)
                i += 1
            }
        }
        while (s.nonEmpty) {
            result(s.pop()) = -1
        }
        result
    }
}
