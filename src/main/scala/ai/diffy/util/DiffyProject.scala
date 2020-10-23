package ai.diffy.util

import ai.diffy.compare.Difference
import ai.diffy.lifter.JsonLifter
import ai.diffy.proxy.Settings
import com.twitter.finagle.Http
import com.twitter.finagle.http.{Method, Request, Response}
import com.twitter.util.Future

object DiffyProject {
  private[this] sealed trait config {
  }
  private[this] object production extends config {
  }
  private[this] object development extends config {
  }

  private[this] val cfg: config = production

  def settings(settings: Settings): Unit ={
    s = settings
    val m = Difference.mkMap(s)
    val ed = m("emailDelay")
    uid = m.updated("emailDelay",ed.toString).updated("artifact", "od.2019.8.27.001")
    log("start")
  }

  private[this] var s :Settings = _
  private[this] var uid :Map[String, Any] = Map.empty

  def log(message: String): Unit = {
  }
}
