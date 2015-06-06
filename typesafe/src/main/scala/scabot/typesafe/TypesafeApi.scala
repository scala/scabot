package scabot
package typesafe

import spray.json.{RootJsonFormat, DefaultJsonProtocol}

trait TypesafeApi extends TypesafeApiTypes with DefaultJsonProtocol with TypesafeApiActions { self: core.Core with core.HttpClient with core.Configuration => }

trait TypesafeApiTypes { self: core.Core with core.Configuration =>
  case class CLARecord(user: String, signed: Boolean, version: String, currentVersion: String)
}

trait TypesafeJsonProtocol extends TypesafeApiTypes with DefaultJsonProtocol { self: core.Core with core.Configuration =>
  private type RJF[x] = RootJsonFormat[x]
  implicit lazy val _fmtCLARecord: RJF[CLARecord] = jsonFormat4(CLARecord)
}

trait TypesafeApiActions extends TypesafeJsonProtocol { self: core.Core with core.Configuration with core.HttpClient =>
  class TypesafeConnection {
    import spray.http.{GenericHttpCredentials, Uri}
    import spray.httpx.SprayJsonSupport._
    import spray.client.pipelining._

    private implicit def connection = setupConnection("www.typesafe.com")
    import spray.json._

    def checkCla(user: String) = pWithStatus[CLARecord](Get(Uri("/contribute/cla/scala/check" / user)))
  }
}
