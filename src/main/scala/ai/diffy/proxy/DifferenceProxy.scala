package ai.diffy.proxy

import java.util.concurrent.atomic.AtomicInteger

import ai.diffy.analysis._
import ai.diffy.lifter.Message
import ai.diffy.util.ServiceInstance

import com.google.inject.Provides
import com.twitter.finagle._
import com.twitter.finagle.tracing.Trace
import com.twitter.inject.TwitterModule
import com.twitter.logging.Logger
import com.twitter.util._
import javax.inject.Singleton

object DifferenceProxyModule extends TwitterModule {
  @Provides
  @Singleton
  def providesDifferenceProxy(
    settings: Settings,
    collector: InMemoryDifferenceCollector,
    joinedDifferences: JoinedDifferences,
    analyzer: DifferenceAnalyzer
  ): DifferenceProxy =
    settings.protocol match {
      case "thrift" => ThriftDifferenceProxy(settings, collector, joinedDifferences, analyzer)
      case "http" => SimpleHttpDifferenceProxy(settings, collector, joinedDifferences, analyzer)
      case "https" => SimpleHttpsDifferenceProxy(settings, collector, joinedDifferences, analyzer)
    }
}

object DifferenceProxy {
  object NoResponseException extends Exception("No responses provided by diffy")
  val NoResponseExceptionFuture = Future.exception(NoResponseException)
  val log = Logger(classOf[DifferenceProxy])
}

trait DifferenceProxy {
  import DifferenceProxy._

  type Req
  type Rep
  type Srv <: ClientService[Req, Rep]

  val server: ListeningServer
  val settings: Settings
  var lastReset: Time = Time.now

  def serviceFactory(serverset: String, label: String): Srv

  def liftRequest(req: Req): Future[Message]
  def liftResponse(rep: Try[Rep]): Future[Message]

  // Clients for services
  val candidate = serviceFactory(settings.candidate, "candidate")
  val primary   = serviceFactory(settings.primary, "primary")
  val secondary = serviceFactory(settings.secondary, "secondary")

  val collector: InMemoryDifferenceCollector

  val joinedDifferences: JoinedDifferences

  val analyzer: DifferenceAnalyzer

  private[this] lazy val multicastHandler = {
    val clients = Seq(primary, candidate, secondary) map { _.client }
    val responseIndex = ServiceInstance.all.indexOf(settings.responseMode)
    if (settings.parallel) new ParallelMulticastService(clients, responseIndex) else new SequentialMulticastService(clients, responseIndex)

  }

  val outstandingRequests = new AtomicInteger(0)
  def proxy = new Service[Req, Rep] {
    override def apply(req: Req): Future[Rep] = {
      Trace.disable()
      outstandingRequests.incrementAndGet()
      val (proxyResponse : Future[(Try[Rep])], rawResponses: Future[Seq[(Try[Rep], Long, Long)]]) =
        multicastHandler.apply(req)

      (rawResponses onFailure {
        t => log.debug(t, "error networking")
      } : Future[Seq[(Try[Rep], Long, Long)]]) flatMap { rs =>
        log.debug("success networking" )
        Future.collect(rs map {case (r, start, end) => liftResponse(r) map { (_, start, end)} })
      } respond {
        case Return(rs) =>
          log.debug(s"success lifting ${rs.head._1.endpoint}")

        case Throw(t) => log.debug(t, "error lifting")
      } flatMap  {
      case Seq(primaryResponse, candidateResponse, secondaryResponse) =>
        liftRequest(req) respond {
          case Return(m) =>
            log.debug(s"success lifting request for ${m.endpoint}")

          case Throw(t) => log.debug(t, "error lifting request")
        } map { req =>
          analyzer(req, candidateResponse, primaryResponse, secondaryResponse)
        }
      } respond { _ => outstandingRequests.decrementAndGet }

      proxyResponse flatMap { Future.const }
    }
  }

  def bypassRequest(req: Req): Future[Rep] = {
    val service = Seq(primary, candidate, secondary)(ServiceInstance.all.indexOf(settings.responseMode))
    service.client(req)
  }

  def clear() = {
    lastReset = Time.now
    analyzer.clear()
  }
}
