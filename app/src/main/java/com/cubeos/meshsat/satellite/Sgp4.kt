package com.cubeos.meshsat.satellite

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Pure Kotlin SGP4 orbit propagator for LEO satellites.
 * Based on Vallado's reference implementation (2006) and AIAA 2006-6753.
 *
 * Propagates TEME (True Equator Mean Equinox) position/velocity from TLE elements.
 */
object Sgp4 {

    // WGS-84 constants
    private const val EARTH_RADIUS_KM = 6378.137
    private const val MU = 398600.4418           // km³/s²
    private const val J2 = 1.08262998905e-3
    private const val J3 = -2.53215306e-6
    private const val J4 = -1.61098761e-6
    private const val KE = 7.43669161331734e-2   // sqrt(GM) in earth-radii^1.5/min
    private const val MIN_PER_DAY = 1440.0
    private const val TWO_PI = 2.0 * PI
    private const val DEG2RAD = PI / 180.0
    private const val XPDOTP = MIN_PER_DAY / TWO_PI  // 229.1831

    // Derived constants
    private const val CK2 = 0.5 * J2
    private const val CK4 = -0.375 * J4
    private const val QOMS2T = 1.880279159015271e-9  // ((120-78)/EARTH_RADIUS_KM)^4
    private const val S = 1.01222928  // (1 + 78/EARTH_RADIUS_KM)
    private const val A3OVK2 = J3 / CK2

    data class EciPosition(val x: Double, val y: Double, val z: Double)

    /**
     * Propagate a TLE to a given time (minutes since TLE epoch).
     * Returns TEME position in km, or null if the orbit has decayed.
     */
    fun propagate(tle: TleElements, tsince: Double): EciPosition? {
        val satrec = initSgp4(tle) ?: return null
        return propagateInternal(satrec, tsince)
    }

    private class SatRec(
        val no: Double,       // mean motion (rad/min)
        val ecco: Double,     // eccentricity
        val inclo: Double,    // inclination (rad)
        val nodeo: Double,    // RAAN (rad)
        val argpo: Double,    // argument of perigee (rad)
        val mo: Double,       // mean anomaly (rad)
        val bstar: Double,    // drag term
        val ndot: Double,     // mean motion first derivative (rad/min²)
        val nddot: Double,    // mean motion second derivative (rad/min³)

        // Computed initialization values
        var isimp: Boolean = false,
        var aodp: Double = 0.0,
        var con41: Double = 0.0,
        var cc1: Double = 0.0,
        var cc4: Double = 0.0,
        var cc5: Double = 0.0,
        var d2: Double = 0.0,
        var d3: Double = 0.0,
        var d4: Double = 0.0,
        var eta: Double = 0.0,
        var argpdot: Double = 0.0,
        var omgcof: Double = 0.0,
        var sinmao: Double = 0.0,
        var t2cof: Double = 0.0,
        var t3cof: Double = 0.0,
        var t4cof: Double = 0.0,
        var t5cof: Double = 0.0,
        var x1mth2: Double = 0.0,
        var x7thm1: Double = 0.0,
        var mdot: Double = 0.0,
        var nodedot: Double = 0.0,
        var xlcof: Double = 0.0,
        var xmcof: Double = 0.0,
        var xnodcf: Double = 0.0,
        var delmo: Double = 0.0,
        var con42: Double = 0.0,
    )

    private fun initSgp4(tle: TleElements): SatRec? {
        val no = tle.meanMotion / XPDOTP  // revs/day → rad/min
        val ecco = tle.eccentricity
        val inclo = tle.inclination * DEG2RAD
        val nodeo = tle.raan * DEG2RAD
        val argpo = tle.argPerigee * DEG2RAD
        val mo = tle.meanAnomaly * DEG2RAD
        val bstar = tle.bstar
        val ndot = tle.meanMotionDot / (XPDOTP * MIN_PER_DAY)
        val nddot = tle.meanMotionDDot / (XPDOTP * MIN_PER_DAY * MIN_PER_DAY)

        val rec = SatRec(no, ecco, inclo, nodeo, argpo, mo, bstar, ndot, nddot)

        val cosio = cos(inclo)
        val sinio = sin(inclo)
        val cosio2 = cosio * cosio
        rec.x1mth2 = 1.0 - cosio2
        rec.con41 = -rec.x1mth2 - cosio2 - cosio2
        rec.x7thm1 = 7.0 * cosio2 - 1.0
        rec.con42 = 1.0 - 5.0 * cosio2

        val theta2 = cosio2
        val theta4 = theta2 * theta2
        val betao2 = 1.0 - ecco * ecco
        val betao = sqrt(betao2)
        val a1 = (KE / no).pow(2.0 / 3.0)
        val del1 = 1.5 * CK2 * (3.0 * theta2 - 1.0) / (a1 * a1 * betao * betao2)
        val ao = a1 * (1.0 - del1 * (1.0 / 3.0 + del1 * (1.0 + 134.0 / 81.0 * del1)))
        val delo = 1.5 * CK2 * (3.0 * theta2 - 1.0) / (ao * ao * betao * betao2)
        val xnodp = no / (1.0 + delo)
        rec.aodp = ao / (1.0 - delo)

        // Perigee check
        val perigee = (rec.aodp * (1.0 - ecco) - 1.0) * EARTH_RADIUS_KM
        rec.isimp = perigee < 220.0

        var s4 = S
        var qoms24 = QOMS2T
        if (perigee < 156.0) {
            s4 = if (perigee < 98.0) 20.0 / EARTH_RADIUS_KM + 1.0
            else (perigee - 78.0) / EARTH_RADIUS_KM + 1.0
            qoms24 = ((120.0 - (s4 - 1.0) * EARTH_RADIUS_KM) / EARTH_RADIUS_KM).pow(4.0)
        }

        val pinvsq = 1.0 / (rec.aodp * rec.aodp * betao2 * betao2)
        val tsi = 1.0 / (rec.aodp - s4)
        rec.eta = rec.aodp * ecco * tsi
        val etasq = rec.eta * rec.eta
        val eeta = ecco * rec.eta
        val psisq = abs(1.0 - etasq)
        val coef = qoms24 * tsi.pow(4.0)
        val coef1 = coef / psisq.pow(3.5)
        val c2 = coef1 * xnodp * (rec.aodp * (1.0 + 1.5 * etasq + eeta * (4.0 + etasq)) +
                0.75 * CK2 * tsi / psisq * (3.0 * (3.0 * theta2 - 1.0)) * (8.0 + 3.0 * etasq * (8.0 + etasq)))
        rec.cc1 = bstar * c2
        val c3 = if (ecco > 1.0e-4) coef * tsi * A3OVK2 * xnodp * sinio / ecco else 0.0
        rec.cc4 = 2.0 * xnodp * coef1 * rec.aodp * betao2 *
                (rec.eta * (2.0 + 0.5 * etasq) + ecco * (0.5 + 2.0 * etasq) -
                        2.0 * CK2 * tsi / (rec.aodp * psisq) *
                        (-3.0 * (3.0 * (1.0 - 2.0 * eeta + etasq * (1.5 - 0.5 * eeta)) * rec.con41 +
                                0.75 * rec.x1mth2 * (2.0 * etasq - eeta * (1.0 + etasq)) * cos(2.0 * argpo))))
        rec.cc5 = 2.0 * coef1 * rec.aodp * betao2 * (1.0 + 2.75 * (etasq + eeta) + eeta * etasq)

        rec.sinmao = sin(mo)
        rec.xlcof = if (abs(cosio + 1.0) > 1.5e-12)
            -0.25 * A3OVK2 * sinio * (3.0 + 5.0 * cosio) / (1.0 + cosio)
        else
            -0.25 * A3OVK2 * sinio * (3.0 + 5.0 * cosio) / 1.5e-12

        rec.xmcof = if (ecco > 1.0e-4) -TWO_PI * coef * bstar / eeta / 3.0 else 0.0
        rec.xnodcf = 3.5 * betao2 * CK2 * xnodp * pinvsq * sinio * cosio
        rec.t2cof = 1.5 * rec.cc1

        rec.mdot = xnodp + 0.5 * CK2 * pinvsq * betao * (3.0 * theta2 - 1.0) +
                0.0625 * CK2 * CK2 * pinvsq * pinvsq * betao *
                (13.0 - 78.0 * theta2 + 137.0 * theta4)
        rec.argpdot = -0.5 * CK2 * pinvsq * rec.con42 +
                0.0625 * CK2 * CK2 * pinvsq * pinvsq *
                (7.0 - 114.0 * theta2 + 395.0 * theta4) +
                CK4 * pinvsq * pinvsq * (3.0 - 36.0 * theta2 + 49.0 * theta4)
        val xhdot1 = -CK2 * pinvsq * cosio
        rec.nodedot = xhdot1 + (0.5 * CK2 * CK2 * pinvsq * pinvsq * (4.0 - 19.0 * theta2) +
                2.0 * CK4 * pinvsq * pinvsq * (3.0 - 7.0 * theta2)) * cosio
        rec.omgcof = bstar * c3 * cos(argpo)
        rec.delmo = (1.0 + rec.eta * cos(mo)).pow(3.0)

        if (!rec.isimp) {
            val c1sq = rec.cc1 * rec.cc1
            rec.d2 = 4.0 * rec.aodp * tsi * c1sq
            val temp = rec.d2 * tsi * rec.cc1 / 3.0
            rec.d3 = (17.0 * rec.aodp + s4) * temp
            rec.d4 = 0.5 * temp * rec.aodp * tsi * (221.0 * rec.aodp + 31.0 * s4) * rec.cc1
            rec.t3cof = rec.d2 + 2.0 * c1sq
            rec.t4cof = 0.25 * (3.0 * rec.d3 + rec.cc1 * (12.0 * rec.d2 + 10.0 * c1sq))
            rec.t5cof = 0.2 * (3.0 * rec.d4 + 12.0 * rec.cc1 * rec.d3 + 6.0 * rec.d2 * rec.d2 +
                    15.0 * c1sq * (2.0 * rec.d2 + c1sq))
        }

        return rec
    }

    private fun propagateInternal(rec: SatRec, tsince: Double): EciPosition? {
        // Update for secular effects
        val xmdf = rec.mo + rec.mdot * tsince
        val argpdf = rec.argpo + rec.argpdot * tsince
        val nodedf = rec.nodeo + rec.nodedot * tsince
        var argpm = argpdf
        var mm = xmdf
        val t2 = tsince * tsince
        val nodem = nodedf + rec.xnodcf * t2
        var tempa = 1.0 - rec.cc1 * tsince
        var tempe = rec.bstar * rec.cc4 * tsince
        var templ = rec.t2cof * t2

        if (!rec.isimp) {
            val delomg = rec.omgcof * tsince
            val delm = rec.xmcof * ((1.0 + rec.eta * cos(xmdf)).pow(3.0) - rec.delmo)
            val temp = delomg + delm
            mm = xmdf + temp
            argpm = argpdf - temp
            val t3 = t2 * tsince
            val t4 = t3 * tsince
            tempa = tempa - rec.d2 * t2 - rec.d3 * t3 - rec.d4 * t4
            tempe = tempe + rec.bstar * rec.cc5 * (sin(mm) - rec.sinmao)
            templ = templ + rec.t3cof * t3 + t4 * (rec.t4cof + tsince * rec.t5cof)
        }

        val nm = rec.no
        var em = rec.ecco
        val inclm = rec.inclo

        val am = (KE / nm).pow(2.0 / 3.0) * tempa * tempa
        val nm2 = KE / am.pow(1.5)
        em -= tempe
        if (em < 1.0e-6) em = 1.0e-6
        if (em >= 1.0) return null  // orbit decayed

        mm += nm2 * templ
        var xlm = mm + argpm + nodem
        val sinim = sin(inclm)
        val cosim = cos(inclm)

        // Fix mean anomaly
        val axn = em * cos(argpm)
        var temp = 1.0 / (am * (1.0 - em * em))
        val xlcof = rec.xlcof
        val aycof = if (abs(cosim + 1.0) > 1.5e-12)
            -0.5 * A3OVK2 * sinim
        else
            -0.5 * A3OVK2 * sinim
        val ayn = em * sin(argpm) + aycof
        val xl = xlm + xlcof * axn

        // Kepler's equation
        var u = (xl - nodem) % TWO_PI
        if (u < 0) u += TWO_PI
        var eo1 = u
        for (i in 0 until 10) {
            val sineo1 = sin(eo1)
            val coseo1 = cos(eo1)
            val f = u - eo1 + axn * sineo1 - ayn * coseo1
            val fp = 1.0 - axn * coseo1 - ayn * sineo1
            val delta = f / fp
            eo1 -= delta
            if (abs(delta) < 1.0e-12) break
        }

        val sineo1 = sin(eo1)
        val coseo1 = cos(eo1)

        // Short period preliminary quantities
        val ecose = axn * coseo1 + ayn * sineo1
        val esine = axn * sineo1 - ayn * coseo1
        val el2 = axn * axn + ayn * ayn
        val pl = am * (1.0 - el2)
        if (pl < 0.0) return null

        val r = am * (1.0 - ecose)
        val rdot = KE * sqrt(am) * esine / r
        val rvdot = KE * sqrt(pl) / r
        val betal = sqrt(1.0 - el2)
        temp = esine / (1.0 + betal)
        val sinu = am / r * (sineo1 - ayn - axn * temp)
        val cosu = am / r * (coseo1 - axn + ayn * temp)
        var su = atan2(sinu, cosu)

        val sin2u = 2.0 * sinu * cosu
        val cos2u = 2.0 * cosu * cosu - 1.0
        val temp1 = CK2 / pl
        val temp2 = temp1 / pl

        // Short periodics
        val rk = r * (1.0 - 1.5 * temp2 * betal * rec.con41) + 0.5 * temp1 * rec.x1mth2 * cos2u
        su -= 0.25 * temp2 * rec.x7thm1 * sin2u
        val xnode = nodem + 1.5 * temp2 * cosim * sin2u
        val xinc = inclm + 1.5 * temp2 * cosim * sinim * cos2u
        val rdotk = rdot - nm2 * temp1 * rec.x1mth2 * sin2u
        val rvdotl = rvdot + nm2 * temp1 * (rec.x1mth2 * cos2u + 1.5 * rec.con41)

        if (rk < 1.0) return null  // orbit decayed

        // Orientation vectors
        val sinsu = sin(su)
        val cossu = cos(su)
        val sinno = sin(xnode)
        val cosno = cos(xnode)
        val sini = sin(xinc)
        val cosi = cos(xinc)

        val xmx = -sinno * cosi
        val xmy = cosno * cosi
        val ux = xmx * sinsu + cosno * cossu
        val uy = xmy * sinsu + sinno * cossu
        val uz = sini * sinsu

        // Position in km (TEME)
        val rkm = rk * EARTH_RADIUS_KM
        return EciPosition(rkm * ux, rkm * uy, rkm * uz)
    }
}
