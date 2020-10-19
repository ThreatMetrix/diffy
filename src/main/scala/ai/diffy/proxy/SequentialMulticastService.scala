package ai.diffy.proxy

import com.twitter.finagle.Service
import com.twitter.util.{Future, Try}

class SequentialMulticastService[-A, +B](services: Seq[Service[A, B]], responseIndex: Int)
  extends MulticastService[A, B]
{
  def apply(request: A): (Future[Try[B]], Future[Seq[(Try[B], Long, Long)]]) = {
    val doForOne = (acc: Future[Seq[(Try[B], Long, Long)]], service: Service[A, B]) =>
      acc flatMap { responseTriesWithTiming =>
        val start = System.currentTimeMillis()
        val nextResponse: Future[Try[B]] = service(request).liftToTry
        nextResponse map { responseTry: Try[B] => {
          val end  = System.currentTimeMillis()
          responseTriesWithTiming ++ Seq((responseTry, start, end))
        }}
      }

    val (beforeReturn : Seq[Service[A, B]], afterReturn : Seq[Service[A, B]]) = services.splitAt(responseIndex + 1)
    val returnResponses = beforeReturn.foldLeft[Future[Seq[(Try[B], Long, Long)]]](Future.Nil)(doForOne)
    val allResponses = afterReturn.foldLeft[Future[Seq[(Try[B], Long, Long)]]](returnResponses)(doForOne)
    (returnResponses map { result => result.last._1}, allResponses)
  }
}
