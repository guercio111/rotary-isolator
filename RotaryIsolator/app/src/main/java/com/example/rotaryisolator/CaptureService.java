package com.example.rotaryisolator;

import android.app.*;
import android.content.*;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CaptureService extends Service {
    public static final String START="clock.START", STOP="clock.STOP", SYNC="clock.SYNC", OFFSET="clock.OFFSET", STATE="clock.STATE";
    public static final String BPM="clock.BPM", BEAT="clock.BEAT", STATUS="clock.STATUS";
    public static final String EXTRA_CODE="code", EXTRA_DATA="data", EXTRA_OFFSET="offset";
    private static final String CHANNEL="bpm_clock_capture";
    private final AtomicBoolean running=new AtomicBoolean(false);
    private final BeatEngine engine=new BeatEngine();
    private MediaProjection projection;
    private AudioRecord recorder;
    private Thread audioThread, clockThread;
    private volatile boolean syncRequested;
    private volatile long nextBeatNs;
    private volatile long beatPeriodNs;
    private volatile float offset;
    private long lastUiMs;

    @Override public void onCreate(){super.onCreate(); NotificationChannel c=new NotificationChannel(CHANNEL,"BPM Clock",NotificationManager.IMPORTANCE_LOW); getSystemService(NotificationManager.class).createNotificationChannel(c);}
    @Override public int onStartCommand(Intent i,int flags,int id){
        if(i==null)return START_NOT_STICKY;
        String a=i.getAction();
        if(STOP.equals(a)){stopEngine();stopSelf();return START_NOT_STICKY;}
        if(SYNC.equals(a)){syncRequested=true;return START_NOT_STICKY;}
        if(OFFSET.equals(a)){offset=Math.max(-10f,Math.min(10f,i.getFloatExtra(EXTRA_OFFSET,0f)));engine.setManualOffset(offset);return START_NOT_STICKY;}
        if(STATE.equals(a)){broadcastStatus(running.get()?"Analisi BPM attiva: audio originale non modificato":"Cattura arrestata");if(!running.get())stopSelf();return START_NOT_STICKY;}
        if(START.equals(a)&&!running.get()){
            startForeground(21,new Notification.Builder(this,CHANNEL).setContentTitle("BPM Clock Engine").setContentText("Analisi audio in corso").setSmallIcon(android.R.drawable.ic_media_play).build());
            try{
                int code=i.getIntExtra(EXTRA_CODE,0); Intent data=i.getParcelableExtra(EXTRA_DATA);
                MediaProjectionManager m=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
                projection=m.getMediaProjection(code,data);
                if(projection==null)throw new IllegalStateException("MediaProjection nulla");
                projection.registerCallback(new MediaProjection.Callback(){@Override public void onStop(){stopEngine();stopSelf();}},new Handler(Looper.getMainLooper()));
                startAudio();
            }catch(Exception e){broadcastStatus("Avvio fallito: "+e.getMessage());stopEngine();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
        }
        return START_NOT_STICKY;
    }
    private void startAudio() throws Exception{
        int rate=BeatEngine.SAMPLE_RATE, inMask=AudioFormat.CHANNEL_IN_STEREO, outMask=AudioFormat.CHANNEL_OUT_STEREO;
        AudioFormat inf=new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(inMask).build();
        AudioPlaybackCaptureConfiguration cfg=new AudioPlaybackCaptureConfiguration.Builder(projection).addMatchingUsage(AudioAttributes.USAGE_MEDIA).build();
        int min=AudioRecord.getMinBufferSize(rate,inMask,AudioFormat.ENCODING_PCM_16BIT);
        recorder=new AudioRecord.Builder().setAudioFormat(inf).setBufferSizeInBytes(Math.max(min*2,8192)).setAudioPlaybackCaptureConfig(cfg).build();
        if(recorder.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("AudioRecord non inizializzato");
        engine.reset();engine.setManualOffset(offset);
        running.set(true);recorder.startRecording();
        audioThread=new Thread(this::audioLoop,"BPM-Audio");audioThread.start();
        clockThread=new Thread(this::clockLoop,"BPM-Master-Clock");clockThread.start();
        broadcastStatus("Analisi BPM attiva: audio originale non modificato");
    }
    private void audioLoop(){
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        short[] pcm=new short[1024];
        try{
            while(running.get()){
                int n=recorder.read(pcm,0,pcm.length,AudioRecord.READ_BLOCKING);
                if(n<=0){if(n==AudioRecord.ERROR_DEAD_OBJECT)break;continue;}
                int usable=n-(n%2);
                // BPM detection always sees the untouched capture, before any effect.
                engine.process(pcm,usable,SystemClock.elapsedRealtime());
                long now=SystemClock.elapsedRealtime();
                if(now-lastUiMs>=250){broadcastBpm();lastUiMs=now;}
            }
        }catch(Exception ignored){}finally{if(running.get())stopEngine();}
    }
    private void clockLoop(){
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT);
        while(running.get()){
            long now=System.nanoTime();float bpm=engine.getClockBpm();
            if(bpm>0f){
                long period=(long)(60_000_000_000.0/bpm);
                if(beatPeriodNs==0||Math.abs(period-beatPeriodNs)>beatPeriodNs*0.002)beatPeriodNs=period;
                if(syncRequested){nextBeatNs=now+beatPeriodNs;syncRequested=false;}
                if(nextBeatNs==0)nextBeatNs=now+beatPeriodNs;
                if(now>=nextBeatNs){Intent beat=new Intent(BEAT).setPackage(getPackageName()).putExtra("time_ns",nextBeatNs);sendBroadcast(beat);do{nextBeatNs+=beatPeriodNs;}while(nextBeatNs<=now);}
            }
            try{Thread.sleep(2);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
        }
    }
    private void broadcastBpm(){
        Intent i=new Intent(BPM).setPackage(getPackageName());
        i.putExtra("detected",engine.getDetectedBpm());i.putExtra("clock",engine.getClockBpm());
        i.putExtra("confidence",engine.getConfidence());i.putExtra("locked",engine.isLocked());i.putExtra("offset",offset);
        sendBroadcast(i);
    }
    private void broadcastStatus(String s){sendBroadcast(new Intent(STATUS).setPackage(getPackageName()).putExtra("message",s));}
    private synchronized void stopEngine(){
        if(!running.getAndSet(false)){release();return;}
        if(recorder!=null){try{recorder.stop();}catch(Exception ignored){}}
        MediaProjection p=projection;projection=null;if(p!=null){try{p.stop();}catch(Exception ignored){}}
        Thread a=audioThread,c=clockThread;if(a!=null)a.interrupt();if(c!=null)c.interrupt();
        if(a==null||!a.isAlive()||a==Thread.currentThread())release();
        broadcastStatus("Cattura arrestata");
    }
    private synchronized void release(){
        if(recorder!=null){try{recorder.release();}catch(Exception ignored){}recorder=null;}
    }
    @Override public void onDestroy(){stopEngine();super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}