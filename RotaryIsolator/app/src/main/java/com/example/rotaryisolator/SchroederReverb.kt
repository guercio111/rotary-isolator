package com.example.rotaryisolator

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.PI

/**
 * Experimental filtered-room reverb return.
 * Conservative feedback and explicit freeze; dry playback is never altered.
 */
internal class SchroederReverb(private val sampleRate: Int) {
    private class Comb(delay: Int) {
        private val data=FloatArray(delay.coerceAtLeast(1))
        private var index=0
        private var dampingState=0f
        fun tick(input:Float,feedback:Float,damping:Float):Float {
            val delayed=data[index]
            dampingState=delayed*(1f-damping)+dampingState*damping
            data[index]=input+dampingState*feedback
            index=(index+1)%data.size
            return delayed
        }
        fun clear(){data.fill(0f);index=0;dampingState=0f}
    }
    private class AllPass(delay:Int) {
        private val data=FloatArray(delay.coerceAtLeast(1))
        private var index=0
        fun tick(x:Float):Float {
            val d=data[index]
            val y=d-0.5f*x
            data[index]=x+0.5f*y
            index=(index+1)%data.size
            return y
        }
        fun clear(){data.fill(0f);index=0}
    }
    private val combs=arrayOf(
        Comb((sampleRate*0.0297f).toInt()),Comb((sampleRate*0.0371f).toInt()),
        Comb((sampleRate*0.0411f).toInt()),Comb((sampleRate*0.0437f).toInt())
    )
    private val ap1=AllPass((sampleRate*0.0051f).toInt())
    private val ap2=AllPass((sampleRate*0.0017f).toInt())
    private var hpX=0f
    private var hpY=0f
    private var lpY=0f
    private var limiter=1f

    fun clear(){
        combs.forEach{it.clear()};ap1.clear();ap2.clear()
        hpX=0f;hpY=0f;lpY=0f;limiter=1f
    }

    fun process(input:ShortArray,output:ShortArray,count:Int,mixPercent:Int,
                roomSize:Int,hpHz:Int,lpHz:Int,freeze:Boolean) {
        val mix=mixPercent.coerceIn(0,100)/100f
        val size=roomSize.coerceIn(0,1000)/1000f
        val hp=hpHz.coerceIn(20,1200).toFloat()
        val lp=lpHz.coerceIn(1000,20000).toFloat()
        val hpA=exp((-2.0*PI*hp/sampleRate).toFloat())
        val lpA=(1f-exp((-2.0*PI*lp/sampleRate).toFloat())).coerceIn(0.001f,1f)
        // Stronger but bounded feedback curve: SIZE controls tail length without runaway resonance.
        val feedback=if(freeze) 0.985f else (0.62f+size.pow(2.0f)*0.33f).coerceIn(0.62f,0.95f)
        val damping=if(freeze) 0.08f else (0.06f+(1f-size).pow(1.35f)*0.18f)
        for(i in 0 until count) {
            val x=input[i]/32768f
            val hpOut=hpA*(hpY+x-hpX)
            hpX=x;hpY=hpOut
            lpY+=lpA*(hpOut-lpY)
            val send=if(freeze) 0f else lpY*0.48f
            var wet=0f
            for(c in combs) wet+=c.tick(send,feedback,damping)
            wet=ap2.tick(ap1.tick(wet*0.42f))
            val raw=wet*mix*1.15f
            val mag=abs(raw)
            val target=if(mag>0.72f) 0.72f/mag else 1f
            limiter=if(target<limiter) target else limiter+(1f-limiter)*0.002f
            val safe=(raw*limiter).coerceIn(-0.72f,0.72f)
            output[i]=(safe*32767f).toInt().coerceIn(-32768,32767).toShort()
        }
    }
}
