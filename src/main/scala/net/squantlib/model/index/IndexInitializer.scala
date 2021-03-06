package net.squantlib.model.index

import net.squantlib.model.market.Market
import net.squantlib.model.yieldparameter.{YieldParameter, YieldParameter3D}
import org.jquantlib.time.{Period => qlPeriod}
import org.jquantlib.termstructures.{BlackVolatilityTermStructure, BlackVolTermStructure}
import org.jquantlib.termstructures.yieldcurves.FlatForward
import org.jquantlib.daycounters.Actual365Fixed
import org.jquantlib.termstructures.volatilities.LocalVolSurface
import net.squantlib.util.DisplayUtils._
import org.jquantlib.time.{Period => qlPeriod, Date => qlDate}
import net.squantlib.math.volatility.DupireLocalVolatility

/**
 * Index specific discount curve calibration.
 */
trait IndexInitializer {
  
  def getModel(market:Market):Option[Index]
  
  def mult(x:Double):IndexInitializer
  
  def addVol(x:Double):IndexInitializer
  
  def addDividend(x:Double):IndexInitializer
}


case class EmptyInitializer() extends IndexInitializer {
  override def getModel(market:Market):Option[Index] = None
  override def mult(x:Double):IndexInitializer = this
  override def addVol(x:Double):IndexInitializer = this
  override def addDividend(x:Double):IndexInitializer = this
}


case class IndexATMContinuous(
    name:String, 
    ccy:String,
    spot:Double,
    divYield:Map[qlPeriod, Double],
    repo:Map[qlPeriod, Double],
    vol:Map[qlPeriod, Double],
    discountCcy:String,
    discountSpread:Double
    ) extends IndexInitializer {
  
  override def getModel(market:Market):Option[Index] = {
    
    val valuedate = market.valuedate
    
    val dividend:DividendCurve = DividendCurve(valuedate, divYield).orNull
    if (dividend == null) {return None}
    
    val ratecurve = market.getDiscountCurve(ccy, discountCcy, discountSpread).orNull
    if (ratecurve == null) {return None}
    
    val repoCurve:RepoCurve = if (repo.isEmpty) RepoCurve.zeroCurve(valuedate) else RepoCurve(valuedate, repo).getOrElse(RepoCurve.zeroCurve(valuedate))
    
    val volCurve:YieldParameter = if (vol.isEmpty) YieldParameter(valuedate, Double.NaN).get else YieldParameter(valuedate, vol).getOrElse(YieldParameter(valuedate, Double.NaN).get)
    
    Some(SmoothIndex(name, spot, ratecurve, dividend, repoCurve, volCurve))
  }
  
  override def mult(x:Double):IndexInitializer = IndexATMContinuous(
    name, 
    ccy,
    spot * x,
    divYield,
    repo,
    vol,
    discountCcy,
    discountSpread
  )
  
  override def addVol(x:Double):IndexInitializer = IndexATMContinuous(
    name, 
    ccy,
    spot,
    divYield,
    repo,
    vol.map{case (t, v) => (t, v+x)},
    discountCcy,
    discountSpread
  )

  override def addDividend(x:Double):IndexInitializer = IndexATMContinuous(
    name, 
    ccy,
    spot,
    divYield.map{case (t, v) => (t, v+x)},
    repo,
    vol,
    discountCcy,
    discountSpread
  )
}


case class IndexSmiledContinuous(
    name:String, 
    ccy:String,
    spot:Double,
    divYield:Map[qlPeriod, Double],
    repo:Map[qlPeriod, Double],
    atmVol: Map[qlPeriod, Double],
    smiledVol:Map[(qlPeriod, Double), Double],
    discountCcy:String,
    discountSpread:Double
    ) extends IndexInitializer {
  
  override def getModel(market:Market):Option[Index] = {
    
    val valuedate = market.valuedate
    
    val dividend:DividendCurve = DividendCurve(valuedate, divYield).orNull
    if (dividend == null) {return None}
    
    val ratecurve = market.getDiscountCurve(ccy, discountCcy, discountSpread).orNull
    if (ratecurve == null) {return None}
    
    val repoCurve:RepoCurve = if (repo.isEmpty) RepoCurve.zeroCurve(valuedate) else RepoCurve(valuedate, repo).getOrElse(RepoCurve.zeroCurve(valuedate))

    val atmVolCurve:YieldParameter = if (atmVol.isEmpty) YieldParameter(valuedate, Double.NaN).get else YieldParameter(valuedate, atmVol).getOrElse(YieldParameter(valuedate, Double.NaN).get)
    
    val inputVols = smiledVol.map{case ((d, k), v) => ((valuedate.days(d).toDouble, k), v)}.toMap
    
    val smiledVolCurve:YieldParameter3D = YieldParameter3D.construct(valuedate, inputVols, true).orNull
    if (smiledVolCurve == null) {return None}
    
    val samplePoints2:Map[(Double, Double), Double] = inputVols.map{case ((d, k), v) => 
      ((d, k), smiledVolCurve(d * 1.01, k * 1.01))
    }.filter{case ((d, k), v) => !v.isNaN}
    
    val localVol:DupireLocalVolatility = DupireLocalVolatility(smiledVolCurve, ratecurve, dividend, spot)
    
    val samplePoints:Map[(Double, Double), Double] = inputVols.map{case ((d, k), v) => 
      ((d, k), localVol.localVolatility(d, k))
    }.filter{case ((d, k), v) => !v.isNaN}

    val filteredSample = if (samplePoints.size > 0) {
      val sampleAv = samplePoints.values.sum / samplePoints.size.toDouble
      samplePoints.filter{case ((d, k), v) => v >= sampleAv / 3.0 && v <= sampleAv * 3.0}
    } else samplePoints
    
    val localVolSurface = YieldParameter3D.construct(valuedate, filteredSample).orNull
    if (localVolSurface == null) {return None}
    
    if (filteredSample.size > Math.max(smiledVol.size * 0.5, 5)) {
      Some(SmoothIndex(name, spot, ratecurve, dividend, repoCurve, atmVolCurve, true, smiledVolCurve, localVolSurface))
    } else {
      Some(SmoothIndex(name, spot, ratecurve, dividend, repoCurve, atmVolCurve))
    }

    //println("input vols " + inputVols.size)
    //inputVols.toList.sortBy{case ((d, k), v) => (d, k)}.foreach(println)
    
//    println("all samples2 " + samplePoints2.size)
//    samplePoints2.toList.sortBy{case ((d, k), v) => d}.foreach(println)
//    println("vol size " + vol.size)
//    println("all samples " + samplePoints.size)
//    samplePoints.toList.sortBy{case ((d, k), v) => d}.foreach(println)

//    println("filtered samples " + filteredSample.size)
//    filteredSample.toList.sortBy{case ((d, k), v) => d}.foreach(println)
    
    
  }

  override def mult(x:Double):IndexInitializer = IndexSmiledContinuous(
    name, 
    ccy,
    spot * x,
    divYield,
    repo,
    atmVol,
    smiledVol,
    discountCcy,
    discountSpread
  )
  
  override def addVol(x:Double):IndexInitializer = IndexSmiledContinuous(
    name, 
    ccy,
    spot,
    divYield,
    repo,
    atmVol.map{case (d, v) => (d, v+x)},
    smiledVol.map{case ((t, k), v) => ((t, k), v+x)},
    discountCcy,
    discountSpread
  )

  override def addDividend(x:Double):IndexInitializer = IndexSmiledContinuous(
    name, 
    ccy,
    spot,
    divYield.map{case (t, v) => (t, v+x)},
    repo,
    atmVol,
    smiledVol,
    discountCcy,
    discountSpread
  )
}

//case class IndexVolTermStructure (
//    vd: qlDate, 
//    override val minStrike:Double,
//    override val maxStrike:Double,
//    vol: (Double, Double) => Double,
//    override val maxDate: qlDate
//  ) extends BlackVolatilityTermStructure(vd) {
//  
//  override val dayCounter = new Actual365Fixed
//  
//  override def blackVolImpl(maturity:Double, strike:Double):Double = vol(maturity * 365.25, strike)
//}




//
//case class IndexATMContinuous(
//    name:String, 
//    indexparams:Set[RateFXParameter], 
//    ccy:String,
//    discountCcy:String = "USD",
//    discountSpread:Double = 0.00
//    ) extends IndexInitializer {
//  
//  val yieldid = "Yield"
//  val spotid = "Index"
//  val volid = "IndexVol"
//  val repoid = "Repo"
//  
//  override def getModel(market:Market):Option[Index] = {
//    val params = indexparams.groupBy(_.instrument)
//    if (!params.contains(yieldid) || !params.contains(spotid)) {return None}
//    
//    val valuedate = market.valuedate
//    val yldparam:Map[qlPeriod, Double] = params(yieldid).map(p => (new qlPeriod(p.maturity), p.value)) (collection.breakOut)
//    val dividend = DividendCurve(valuedate, yldparam).orNull
//    if (dividend == null) {return None}
//    
//    val spot:Double = params(spotid).head.value
//    
//    val ratecurve = market.getDiscountCurve(ccy, discountCcy, discountSpread).orNull
//    if (ratecurve == null) {return None}
//    
//    val repo = (params.get(repoid) match {
//      case Some(rs) => 
//        val repoparam:Map[qlPeriod, Double] = rs.map(p => (new qlPeriod(p.maturity), p.value)) (collection.breakOut)
//        RepoCurve(valuedate, repoparam)
//      case None => None
//    }).getOrElse(RepoCurve.zeroCurve(valuedate))
//    
//    val vol:YieldParameter = (params.get(volid) match {
//      case Some(vols) => YieldParameter(valuedate, vols.map(p => (new qlPeriod(p.maturity), p.value)).toMap)
//      case None => None
//    }).getOrElse(YieldParameter(valuedate, Double.NaN).get)
//     
//    Some(SmoothIndex(name, spot, ratecurve, dividend, repo, vol))
//  }
//
//}
//
