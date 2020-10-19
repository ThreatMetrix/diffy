package ai.diffy.proxy

import com.twitter.finagle.Service
import com.twitter.util.{Future, Try}

class ParallelMulticastService[-A, +B](services: Seq[Service[A, B]], responseIndex: Int)
  extends MulticastService[A, B]
{
  def apply(request: A): (Future[Try[B]], Future[Seq[(Try[B], Long, Long)]]) = {
    val requests = services map {
      srv => {
        val start = System.currentTimeMillis()
        srv(request).liftToTry map { res =>
          val end = System.currentTimeMillis()
          (res, start, end)
        }
      }
    }
    (requests(responseIndex) map { result => result._1}, Future.collect(requests))
  }
}