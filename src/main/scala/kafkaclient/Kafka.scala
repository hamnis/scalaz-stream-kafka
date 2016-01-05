package kafkaclient

import scalaz.stream._
import scalaz.concurrent.{Strategy, Task}
import scalaz.syntax.std.boolean._
import java.util.Properties
import kafka.consumer._
import kafka.serializer._
import scodec.bits.ByteVector
import java.util.concurrent.Executors


case class KeyedValue(key: Option[ByteVector], value: ByteVector) {
  def keyAsString = key.flatMap(_.decodeUtf8.right.toOption).getOrElse("")
  def valueAsString = value.decodeUtf8.right.getOrElse("")

  override def toString = s"key: $keyAsString value: $valueAsString"
}


object Kafka {
  
  object ByteVectorDecoder extends Decoder[ByteVector] {
    def fromBytes(bytes: Array[Byte]): ByteVector = ByteVector(bytes)
  }

  def consumer(zookeeper: List[String], gid: String): ConsumerConnector = {
    val p = new Properties()
    p.setProperty("zookeeper.connect", zookeeper.mkString(","))
    p.setProperty("group.id", gid)
    p.setProperty("zookeeper.session.timeout.ms", "400")
    p.setProperty("zookeeper.sync.time.ms", "200")
    p.setProperty("auto.commit.interval.ms", "1000")
    p.setProperty("auto.offset.reset", "smallest")
    consumer(p)
  }

  def consumer(p: Properties): ConsumerConnector = {
    val c = new ConsumerConfig(p)
    Consumer.create(c)
  }

  def subscribe(conn: ConsumerConnector, topic: String, nPartitions: Int = 1): Process[Task, KeyedValue] = {
    val streams = conn.createMessageStreams(
      Map(topic -> nPartitions),
      ByteVectorDecoder,
      ByteVectorDecoder
    )(topic)

    val ec = Executors.newFixedThreadPool(nPartitions, new NamedThreadFactory("KafkaClient"))

    val strat = Strategy.Executor(ec)

    val procs: List[Process[Task, KeyedValue]] = streams.map { i =>
      Process.unfold(i.iterator) { k =>
        k.hasNext.option {
          val next = k.next
          KeyedValue(Option(next.key()), next.message()) -> k
        }
      }
    }

    val merged = merge.mergeN(Process.emitAll(procs))(strat)

    merged.onComplete {
      Process.eval_(Task {
        conn.shutdown()
        ec.shutdown()
      })
    }
  }
}
