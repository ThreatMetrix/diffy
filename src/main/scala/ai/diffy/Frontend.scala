package ai.diffy

import java.util.concurrent.atomic.AtomicInteger

import ai.diffy.IsotopeSdkModule.IsotopeClient
import ai.diffy.proxy.Settings
import com.twitter.finagle.Http
import com.twitter.finagle.http.Request
import com.twitter.finatra.http.Controller
import javax.inject.Inject

class Frontend @Inject()(settings: Settings, isotopeClient: IsotopeClient) extends Controller {

  case class Dashboard(
    serviceName: String,
    serviceClass: String,
    apiRoot: String,
    excludeNoise: Boolean,
    relativeThreshold: Double,
    absoluteThreshold: Double,
    hasIsotope: Boolean = false)

  val reasonIndex = new AtomicInteger(0)
  get("/") { req: Request =>
    response.ok.view(
      "dashboard.mustache",
      Dashboard(
        settings.serviceName,
        settings.serviceClass,
        settings.apiRoot,
        req.params.getBooleanOrElse("exclude_noise", false),
        settings.relativeThreshold,
        settings.absoluteThreshold,
        isotopeClient.isConcrete
      )
    )
  }

  get("/css/:*") { request: Request =>
    response.ok.file(request.path)
  }

  get("/scripts/:*") { request: Request =>
    response.ok.file(request.path)
  }
}