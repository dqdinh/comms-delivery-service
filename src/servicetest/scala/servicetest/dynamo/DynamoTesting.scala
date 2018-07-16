package servicetest.dynamo

import com.amazonaws.services.dynamodbv2.model.AmazonDynamoDBException
import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType._
import com.gu.scanamo.{Scanamo, Table}
import com.ovoenergy.comms.model.{CommManifest, Service, TemplateManifest}
import com.ovoenergy.comms.templates.model.Brand
import com.ovoenergy.comms.templates.model.template.metadata.{TemplateId, TemplateSummary}
import com.ovoenergy.comms.templates.util.Hash
import com.ovoenergy.delivery.service.util.LocalDynamoDb

trait DynamoTesting extends DynamoFormats {

  val dynamoUrl                = "http://localhost:8000"
  val dynamoClient             = LocalDynamoDb.client(dynamoUrl)
  val templateSummaryTableName = "templateSummaryTable"
  val templateSummaryTable     = Table[TemplateSummary](templateSummaryTableName)

  def populateTemplateSummaryTable(ts: TemplateSummary) = {
    Scanamo.exec(dynamoClient)(templateSummaryTable.put(ts))
  }

  def populateTemplateSummaryTable(templateManifest: TemplateManifest) = {
    val ts = TemplateSummary(
      TemplateId(templateManifest.id),
      "blah blah blah",
      Service,
      Brand.Ovo,
      templateManifest.version
    )
    Scanamo.exec(dynamoClient)(templateSummaryTable.put(ts))
  }

  def populateTemplateSummaryTable(commManifest: CommManifest) = {
    val ts = TemplateSummary(
      TemplateId(Hash(commManifest.name)),
      "blah blah blah",
      Service,
      Brand.Ovo,
      commManifest.version
    )
    Scanamo.exec(dynamoClient)(templateSummaryTable.put(ts))
  }

  def createTemplateSummaryTable() = {

    LocalDynamoDb.createTable(dynamoClient)(templateSummaryTableName)('templateId -> S)
    waitUntilTableMade(50)

    def waitUntilTableMade(noAttemptsLeft: Int): (String) = {
      try {
        val summaryTableStatus = dynamoClient.describeTable(templateSummaryTableName).getTable.getTableStatus
        if (summaryTableStatus != "ACTIVE" && noAttemptsLeft > 0) {
          Thread.sleep(20)
          waitUntilTableMade(noAttemptsLeft - 1)
        } else (templateSummaryTableName)
      } catch {
        case e: AmazonDynamoDBException => {
          Thread.sleep(20)
          waitUntilTableMade(noAttemptsLeft - 1)
        }
      }
    }
  }

}
