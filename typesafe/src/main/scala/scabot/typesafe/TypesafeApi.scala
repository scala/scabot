package scabot
package typesafe

import spray.json.{RootJsonFormat, DefaultJsonProtocol}

trait TypesafeApi extends TypesafeApiTypes with DefaultJsonProtocol with TypesafeApiActions

trait TypesafeApiTypes {
  case class CLARecord(user: String, signed: Boolean, version: Option[String], currentVersion: String)
}

trait TypesafeJsonProtocol extends TypesafeApiTypes with DefaultJsonProtocol {
  private type RJF[x] = RootJsonFormat[x]
  implicit lazy val _fmtCLARecord: RJF[CLARecord] = jsonFormat4(CLARecord)
}

trait TypesafeApiActions extends TypesafeJsonProtocol with core.HttpClient {
  class TypesafeConnection {
    import spray.http.{GenericHttpCredentials, Uri}
    import spray.httpx.SprayJsonSupport._
    import spray.client.pipelining._

    private implicit def connection = setupConnection("www.typesafe.com")

    def checkCla(user: String) = pWithStatus[CLARecord](Get(Uri("/contribute/cla/scala/check" / user)))
  }
}
