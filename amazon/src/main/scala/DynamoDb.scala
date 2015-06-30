package scabot
package amazon


import com.amazonaws.auth._
import com.amazonaws.regions._
import com.amazonaws.services.dynamodbv2._
import com.amazonaws.services.dynamodbv2.util.Tables
import com.amazonaws.services.dynamodbv2.model._
import com.amazonaws.services.dynamodbv2.document._

import scala.collection.JavaConverters._

import scala.concurrent.Future

/**
 * Created by adriaan on 1/23/15.
 */
trait DynamoDb extends core.Core {
  class DynoDbClient {
    private lazy val dynamoDBClient = {
      val ipcp   = new InstanceProfileCredentialsProvider()
      val client = new AmazonDynamoDBClient(ipcp)
      client.setRegion(Regions.getCurrentRegion)
      client
    }

    private lazy val dynamoDB = {
      new DynamoDB(dynamoDBClient)
    }

    class DynoDbTable(val name: String) extends Table(dynamoDBClient, name) {
      private def describeTable = dynamoDBClient.describeTable(new DescribeTableRequest(name)).getTable
      private def createTable(request: CreateTableRequest): CreateTableResult = dynamoDBClient.createTable(request)

      def create(schema: List[(String, KeyType)], attribs: List[(String, String)], readCapa: Long = 25, writeCapa: Long = 25): Future[TableDescription] = Future {
        def attrib = new AttributeDefinition
        def key    = new KeySchemaElement
        def provi  = new ProvisionedThroughput

        val request = (new CreateTableRequest).withTableName(name)
          .withKeySchema(schema.map { case (name, tp) => key.withAttributeName(name).withKeyType(tp)}.asJava)
          .withAttributeDefinitions(attribs.map { case (name, tp) => attrib.withAttributeName(name).withAttributeType(tp)}.asJava)
          .withProvisionedThroughput(provi.withReadCapacityUnits(readCapa).withWriteCapacityUnits(writeCapa))

        val table = createTable(request)

        Tables.waitForTableToBecomeActive(dynamoDBClient, name)

        table.getTableDescription
      }

      def exists: Boolean =
        try describeTable.getTableStatus == TableStatus.ACTIVE.toString
        catch { case rnfe: ResourceNotFoundException => false }

      def put(item: Item) = Future { putItem(item) }
      def get(key: PrimaryKey) = Future { Option(getItem(key)) }
    }
  }
}
