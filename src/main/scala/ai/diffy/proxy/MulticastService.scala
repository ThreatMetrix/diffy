package ai.diffy.proxy

import com.twitter.util.{Future, Try}

trait MulticastService[-A, +B]
{
  def apply(request: A): (Future[Try[B]], Future[Seq[(Try[B], Long, Long)]])
}
