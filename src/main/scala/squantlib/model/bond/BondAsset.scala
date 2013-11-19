package squantlib.model.bond

import squantlib.model.asset.AnalyzedAsset
import squantlib.database.schemadefinitions.{Bond => dbBond}
import squantlib.util.Date
import squantlib.database.DB

trait BondAsset extends AnalyzedAsset {
  
  val db:dbBond
  
  override val assetStartDate:Option[Date] = Some(db.issueDate)
  
  override val assetEndDate:Option[Date] = Some(db.endDate)
  
  override def isPriced:Boolean
  
  override def latestPriceLocalCcy: Option[Double]
  
  override val assetID = "PRICE"
    
  override val assetName = db.id
    
  override def getPriceHistory = DB.getHistorical("BONDJPY:" + assetName).mapValues(v => v / 100.0)
  
  override def latestPrice:Option[Double]
  
  override def expectedYield:Option[Double]
  
  override def expectedCoupon:Option[Double]
  
  override def getDbForwardPrice = DB.getForwardPrices("BOND", assetName).mapValues(v => v / 100.0)
  
} 

