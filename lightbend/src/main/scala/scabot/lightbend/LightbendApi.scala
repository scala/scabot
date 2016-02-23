package scabot
package lightbend

import spray.json.{RootJsonFormat, DefaultJsonProtocol}

trait LightbendApi extends LightbendApiTypes with DefaultJsonProtocol with LightbendApiActions

trait LightbendApiTypes {
  case class CLARecord(user: String, signed: Boolean, version: Option[String], currentVersion: String)
}

trait LightbendJsonProtocol extends LightbendApiTypes with DefaultJsonProtocol {
  private type RJF[x] = RootJsonFormat[x]
  implicit lazy val _fmtCLARecord: RJF[CLARecord] = jsonFormat4(CLARecord)
}

trait LightbendApiActions extends LightbendJsonProtocol with core.HttpClient {
  class LightbendConnection {
    import spray.http.{GenericHttpCredentials, Uri}
    import spray.httpx.SprayJsonSupport._
    import spray.client.pipelining._

    private implicit def connection = setupConnection("www.lightbend.com")

    def checkCla(user: String) = pWithStatus[CLARecord](Get(Uri("/contribute/cla/scala/check" / user)))
  }
}
