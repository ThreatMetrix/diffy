package ai.diffy.lifter

import ai.diffy.ParentSpec
import com.google.common.net.MediaType
import com.twitter.finagle.http._
import com.twitter.finatra.http.HttpHeaders
import com.twitter.util.{Await, Throw, Try}
import org.junit.runner.RunWith
import org.scalatest.Matchers._
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class HttpLifterSpec extends ParentSpec {
  object Fixture {
    val reqUri =         "http://localhost:0/0/accounts?private=somePrivateString"

    val jsonContentType = MediaType.JSON_UTF_8.toString
    val textContentType = MediaType.PLAIN_TEXT_UTF_8.toString
    val htmlContentType = MediaType.HTML_UTF_8.toString

    val controllerEndpoint = "account/index"

    val validJsonBody =
      "{" +
        "\"data_type\": \"account\"," +
        "\"data\": [" +
          "{" +
            "\"name\": \"Account 1\"," +
            "\"deleted\": false" +
          "}," +
          "{" +
            "\"name\": \"Account 2\"," +
            "\"deleted\": true" +
          "}" +
        "]," +
        "\"total_count\": 2," +
        "\"next_cursor\": null" +
      "}"

    val invalidJsonBody = "invalid"

    val validHtmlBody = """<html><head><title>Sample HTML</title></head><body><div class="header"><h1 class="box">Hello World</h1></div><p>Lorem ipsum dolor sit amet.</p></body></html>"""

    val testException = new Exception("test exception")

    def request(method: Method, uri: String, body: Option[String] = None): Request = {
      val req = Request(Version.Http11, method, uri)
      body foreach { req.setContentString }
      req
    }

    def response(status: Status, body: String): Response = {
      val resp = Response()
      resp.status = status
      resp.headerMap
        .add(HttpHeaders.ContentLength, body.length.toString)
        .add(HttpHeaders.ContentType, jsonContentType)
        .add(HttpLifter.ControllerEndpointHeaderName, controllerEndpoint)
      resp.setContentString(body)
      resp
    }
  }

  describe("HttpLifter") {
    import Fixture._
    describe("LiftRequest") {
      it("lift simple Get request") {
        val lifter = new HttpLifter(false)
        val req = request(Method.Get, reqUri)
        req.headerMap.add("Canonical-Resource", "endpoint")

        val msg = Await.result(lifter.liftRequest(req))
        val resultFieldMap = msg.result.asInstanceOf[FieldMap[String]]

        msg.endpoint.get should equal ("endpoint")
        resultFieldMap.get("uri").get should equal (req.uri)
      }

      it("lift simple Post request") {
        val lifter = new HttpLifter(false)
        val requestBody = "request_body"
        val req = request(Method.Post, reqUri, Some(requestBody))
        req.headerMap.add("Canonical-Resource", "endpoint")

        val msg = Await.result(lifter.liftRequest(req))
        val resultFieldMap = msg.result.asInstanceOf[FieldMap[String]]

        msg.endpoint.get should equal ("endpoint")
        resultFieldMap.get("uri").get should equal (req.uri)
      }
    }

    describe("LiftResponse") {

      it("lowercase all headers when lifting") {
        val lifter = new HttpLifter(false)
        val resp = response(Status.Ok, validJsonBody)
        resp.headerMap.add("Hello", "World")

        val msg = Await.result(lifter.liftResponse(Try(resp)))
        val resultFieldMap = msg.result

        resultFieldMap("200")
          .asInstanceOf[Map[String, _]]("headers")
          .asInstanceOf[FieldMap[_]]("hello") should be (Seq("World"))
      }
      it("exclude header in response map if excludeHttpHeadersComparison flag is off") {
        val lifter = new HttpLifter(true)
        val resp = response(Status.Ok, validJsonBody)

        val msg = Await.result(lifter.liftResponse(Try(resp)))
        val resultFieldMap = msg.result

        resultFieldMap("200").asInstanceOf[Map[String, _]].get("headers") should be (None)
      }


      it("return None as controller endpoint when action header was not set") {
        val lifter = new HttpLifter(false)
        val resp = response(Status.Ok, validJsonBody)
        resp.headerMap.remove(HttpLifter.ControllerEndpointHeaderName)
        val msg = Await.result(lifter.liftResponse(Try(resp)))

        msg.endpoint should equal (None)
      }

      it("propagate exception if response try failed") {
        val lifter = new HttpLifter(false)
        val thrown = the [Exception] thrownBy {
          Await.result(lifter.liftResponse(Throw(testException)))
        }

        thrown should be (testException)
      }


      val sensitiveJsonBody =
        "{" +
          "\"public\": {\"open\" : \"visible\"}," +
          "\"private\": {\"secret\" : \"forbidden\"}" +
        "}"

      it("censor sensitive parameters") {
        val lifter = new HttpLifter(false, None, Some(Set[String]("private")))
        val requestBody = sensitiveJsonBody
        val req = request(Method.Post, reqUri, Some(requestBody))
        req.headerMap.add("Canonical-Resource", "endpoint")

        val msg = Await.result(lifter.liftRequest(req))
        val resultFieldMap: FieldMap[Any]= msg.result

        msg.endpoint.get should equal ("endpoint")
        resultFieldMap.get("uri").get should equal ("http://localhost:0/0/accounts?private=xxxxxxxxxxxxxxxxx")
        val body = resultFieldMap.get("body")
        body mustBe a [Option[FieldMap[_]]]
        body should be ('defined)
        val value = body.get.asInstanceOf[FieldMap[Any]].get("value")
        value mustBe a [Option[FieldMap[_]]]
        value should be ('defined)
        val valueFieldMap : FieldMap[_] = value.get.asInstanceOf[FieldMap[Any]]
        valueFieldMap.get("private").get should be ("redacted")
        valueFieldMap.get("public").get should not be ("redacted")
      }

      it("lift simple text request") {
        val lifter = new HttpLifter(false)
        val lifted = lifter.liftText("a = b\nc = d\n");
        lifted mustBe a [FieldMap[_]]
        val liftedFieldMap : FieldMap[_] = lifted.asInstanceOf[FieldMap[Any]]
        liftedFieldMap.get("a").get should be ("b")
        liftedFieldMap.get("c").get should be ("d")
      }
    }
  }
}
