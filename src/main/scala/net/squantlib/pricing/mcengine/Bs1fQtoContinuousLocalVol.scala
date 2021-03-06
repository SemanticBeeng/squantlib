package net.squantlib.pricing.mcengine

import net.squantlib.math.random.{RandomGenerator, MersenneTwister}
import net.squantlib.math.statistical.NormSInv
import net.squantlib.model.fx.FX
import net.squantlib.util.DisplayUtils._
import scala.annotation.tailrec
import net.squantlib.model.index.Index

/* Simple Black-Scholes montecarlo pricer.
 * - Continuous dividend
 * - Volatility is constant over time without smile
 * - No rate & dividend volatility
 * @param spot     current underlying price
 * @param rate(t)  continuous compounding risk-free rate of pricing currency at time t as number of years
 * @param dividendYield(t)  continuous compounding risk-free dividend yield at time t as number of years
 * @param sigma(t)  volatility of the underlying FX
 */

class Bs1fQtoContinuousLocalVol(
    spot:Double, 
    rate: Double => Double, 
    dividendYield: Double => Double, 
    localVolatility: (Double, Double) => Double,
    volatilityfx: Double => Double,
    correl:Double)
    extends Montecarlo1f{
  
  override def getRandomGenerator:RandomGenerator = new MersenneTwister(1)

  val smallvalue = 0.00001

  /* Generates FX paths.
   * @param eventdates  FX observation dates as number of years
   * @param paths   Number of Montecarlo paths to be generated
   * @returns Montecarlo paths
   * 
   * CAUTION: Order of event dates are automatically sorted and duplicates removed by the function.
   * Check with first tuple argument for the order of dates.
  */
  
  override def generatePaths(eventDates:List[Double], paths:Int, payoff:List[Double] => List[Double]):(List[Double], List[List[Double]]) = {
    if (eventDates.isEmpty) {return (List.empty, List.empty)}
    
    val randomGenerator = getRandomGenerator
    def normSInv(x:Double) = NormSInv(x)
    
    val dates = eventDates.sorted
    val steps = dates.size
    val stepsize = dates.head :: (dates.tail, dates).zipped.map(_ - _)

    val ratedom = dates.map(rate)
    val ratefor = dates.map(dividendYield)
    //val sigma = dates.map(volatility)
    val volfx = dates.map(volatilityfx)

    val fratedom:List[Double] = ratedom.head :: acc[Double](ratedom, dates, (r0, r1, t0, t1) => (r1 * t1 - r0 * t0) / (t1 - t0), 0.0, List.empty)
    
    val fratefor:List[Double] = ratefor.head :: acc[Double](ratefor, dates, (r0, r1, t0, t1) => (r1 * t1 - r0 * t0) / (t1 - t0), 0.0, List.empty)

    val rtStepsize: List[Double] = stepsize.map(ss => math.sqrt(ss))
    
    //val fsigma:List[Double] = sigma.head :: acc[Double](sigma, dates, (a0, a1, b0, b1) => math.sqrt(math.max(0.00001, (a1 * a1 * b1 - a0 * a0 * b0) / (b1 - b0))), 0.0, List.empty)
    
    val fsigmafx:List[Double] = volfx.head :: acc[Double](volfx, dates, (a0, a1, b0, b1) => math.sqrt(math.max(0.00001, (a1 * a1 * b1 - a0 * a0 * b0) / (b1 - b0))), 0.0, List.empty)
    
    //val drift:List[Double] = driftacc(fratedom, fratefor, fsigma, fsigmafx, stepsize, List.empty)
    
    //val sigt:List[Double] = (fsigma, stepsize).zipped.map{case (sig, ss) => sig * math.sqrt(ss)}
  
    @tailrec def getApath(edates:List[Double], steps:List[Double], sqSteps:List[Double], fSigFx:List[Double], rDom:List[Double], rFor:List[Double], current:List[Double]):List[Double] = {
      if (steps.isEmpty) payoff(current.reverse.tail)
      else {
          val rnd = randomGenerator.sample
          val ninv1 = normSInv(rnd)
          val prevSpot = current.head
          val fsigma = localVolatility(edates.head, prevSpot)
          val drift = (rDom.head - rFor.head - correl * fsigma * fSigFx.head - ((fsigma * fsigma) / 2.0)) * steps.head
          val sigt = fsigma * sqSteps.head
          
          val spot = if (steps.head < smallvalue) prevSpot else prevSpot * scala.math.exp(drift + (sigt * ninv1))
          getApath(edates.tail, steps.tail, sqSteps.tail, fSigFx.tail, rDom.tail, rFor.tail, spot :: current)
        }
      }
    
    @tailrec def getPathes(nbpath:Int, current:List[List[Double]]):List[List[Double]] = {
      if (nbpath == 0) current
      else getPathes(nbpath - 1, getApath(dates, stepsize, rtStepsize, fsigmafx, fratedom, fratefor, List(spot))::current)
    }
  
    val genpaths = getPathes(paths, List.empty)
    (dates, genpaths)
  }

  override def generatePrice(eventDates:List[Double], paths:Int, payoff:List[Double] => List[Double]):(List[Double], List[Double]) = {
    if (eventDates.isEmpty) {return (List.empty, List.empty)}
    
    val randomGenerator = getRandomGenerator
    def normSInv(x:Double) = NormSInv(x)
    
    val dates = eventDates.sorted
    val steps = dates.size
    val stepsize = dates.head :: (dates.tail, dates).zipped.map(_ - _)
    val rtStepsize: List[Double] = stepsize.map(ss => math.sqrt(ss))

    val ratedom = dates.map(rate)
    val ratefor = dates.map(dividendYield)
    //val sigma = dates.map(d => localVolatility(d, spot))
    val volfx = dates.map(volatilityfx)

    val fratedom:List[Double] = ratedom.head :: acc[Double](ratedom, dates, (r0, r1, t0, t1) => (r1 * t1 - r0 * t0) / (t1 - t0), 0.0, List.empty)
    
    val fratefor:List[Double] = ratefor.head :: acc[Double](ratefor, dates, (r0, r1, t0, t1) => (r1 * t1 - r0 * t0) / (t1 - t0), 0.0, List.empty)
    
    //val fsigma:List[Double] = sigma.head :: acc[Double](sigma, dates, (a0, a1, b0, b1) => math.sqrt(math.max(0.00001, (a1 * a1 * b1 - a0 * a0 * b0) / (b1 - b0))), 0.0, List.empty)
    
    val fsigmafx:List[Double] = volfx.head :: acc[Double](volfx, dates, (a0, a1, b0, b1) => math.sqrt(math.max(0.00001, (a1 * a1 * b1 - a0 * a0 * b0) / (b1 - b0))), 0.0, List.empty)
    
    //val drift:List[Double] = driftacc(fratedom, fratefor, fsigma, fsigmafx, stepsize, List.empty)
    
    //val sigt:List[Double] = (fsigma, stepsize).zipped.map{case (sig, ss) => sig * math.sqrt(ss)}
    
    val spotList = List(spot)

    val priceLegs = payoff(List.fill(dates.size)(spot)).size

    @tailrec def getApath(edates:List[Double], steps:List[Double], sqSteps:List[Double], fSigFx:List[Double], rDom:List[Double], rFor:List[Double], current:List[Double]):List[Double] = {
      if (steps.isEmpty) payoff(current.reverse.tail)
      else {
          val rnd = randomGenerator.sample
          val ninv1 = normSInv(rnd)
          val prevSpot = current.head
          val fsigma = localVolatility(edates.head, prevSpot)
          val drift = (rDom.head - rFor.head - correl * fsigma * fSigFx.head - ((fsigma * fsigma) / 2.0)) * steps.head
          val sigt = fsigma * sqSteps.head
          
          val currentSpot = if (steps.head < smallvalue) prevSpot else prevSpot * scala.math.exp(drift + (sigt * ninv1))
          getApath(edates.tail, steps.tail, sqSteps.tail, fSigFx.tail, rDom.tail, rFor.tail, currentSpot :: current)
          
          //val spot = if (steps.head < smallvalue) current.head else current.head * scala.math.exp(drft.head + (siggt.head * ninv1))
          //getApath(steps.tail, drft.tail, siggt.tail, spot :: current)
        }
      }

    @tailrec def getPrices(nbpath:Int, current:List[Double]):List[Double] = 
      if (nbpath == 0) current 
      else getPrices(nbpath - 1, (getApath(dates, stepsize, rtStepsize, fsigmafx, fratedom, fratefor, spotList), current).zipped.map(_ + _))
 
    (dates, getPrices(paths, List.fill(priceLegs)(0.0)).map(a => a / paths.toDouble))
    
  }
  
  @tailrec private def acc[A](r:List[A], t:List[Double], f:(A, A, Double, Double) => A, d:A, current:List[A]):List[A] = 
    if (r.isEmpty || r.tail.isEmpty) current.reverse
    else if (t.tail.head - t.head < smallvalue)  acc(r.tail, t.tail, f, d, d :: current)
    else acc(r.tail, t.tail, f, d, f(r.tail.head, r.head, t.tail.head, t.head) :: current)
    
  @tailrec private def driftacc(rd:List[Double], rf:List[Double], sig:List[Double], sigfx:List[Double], stepp:List[Double], current:List[Double]):List[Double] = 
    if (rd.isEmpty) current.reverse
    else driftacc(rd.tail, rf.tail, sig.tail, sigfx.tail, stepp.tail, (rd.head - rf.head - correl * sig.head * sigfx.head - ((sig.head * sig.head) / 2.0)) * stepp.head :: current)
    
  
  override def modelName = this.getClass.toString
  
  override def spotref = List(spot)
  
  override def scheduledDescription:(List[String], List[List[String]]) = {
    val dates:List[Double] = (for(i <- 1 to 120 if (i <= 12 && i % 3 == 0)|| i % 12 == 0) yield i.toDouble / 12.0).toList
    
    val steps = dates.size
    val stepsize = dates.head :: (dates.tail, dates).zipped.map(_ - _)

    val ratedom = dates.map(rate)
    val ratefor = dates.map(dividendYield)
    val sigma = dates.map(d => localVolatility(d, spot))
    val volfx = dates.map(volatilityfx)

    val fratedom:List[Double] = ratedom.head :: acc[Double](ratedom, dates, (r0, r1, t0, t1) => (r1 * t1 - r0 * t0) / (t1 - t0), 0.0, List.empty)
    
    val fratefor:List[Double] = ratefor.head :: acc[Double](ratefor, dates, (r0, r1, t0, t1) => (r1 * t1 - r0 * t0) / (t1 - t0), 0.0, List.empty)
    
    val fsigma:List[Double] = sigma.head :: acc[Double](sigma, dates, (a0, a1, b0, b1) => math.sqrt(math.max(0.00001, (a1 * a1 * b1 - a0 * a0 * b0) / (b1 - b0))), 0.0, List.empty)
    
    val fsigmafx:List[Double] = volfx.head :: acc[Double](volfx, dates, (a0, a1, b0, b1) => math.sqrt(math.max(0.00001, (a1 * a1 * b1 - a0 * a0 * b0) / (b1 - b0))), 0.0, List.empty)
    
    val drift:List[Double] = driftacc(fratedom, fratefor, fsigma, fsigmafx, stepsize, List.empty)
    
    val sigt:List[Double] = (fsigma, stepsize).zipped.map{case (sig, ss) => sig * math.sqrt(ss)}
    
    var spotprice = spot
    val title = List("valuedate", "forward", "rate ccy1", "rate ccy2", "sigma", "drift", "sigmafx", "correl")
    val schedule = (0 to steps - 1).toList.map(i => {
      spotprice *= scala.math.exp((fratedom(i) - fratefor(i)) * stepsize(i))
      List(dates(i).asDouble, spotprice.asDouble, fratedom(i).asPercent(2), fratefor(i).asPercent(2), fsigma(i).asPercent(2), drift(i).asDouble, fsigma(i).asPercent(2), correl.asPercent(2))
    })
    
    (title, schedule)
  }

}

