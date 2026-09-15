package com.arqapolo.sonatabasstest;

import android.Manifest;
import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.*;
import android.media.audiofx.*;
import android.net.Uri;
import android.provider.Settings;
import android.widget.*;
import java.io.*;
import java.util.*;
import org.json.*;

public class MainActivity extends Activity {
    final int REQ=10, SR=48000;
    final int[] FREQS={50,63,80,100,125,250,500,630,1000};
    final int[] VOLS={20,25,30,35,40,45};
    Button check,start,share; TextView status,instructions,results;
    int vi=0; JSONObject all=new JSONObject(); boolean busy=false;

    @Override public void onCreate(Bundle b){
        super.onCreate(b); setContentView(R.layout.activity_main);
        check=findViewById(R.id.checkBtn); start=findViewById(R.id.startBtn); share=findViewById(R.id.shareBtn);
        status=findViewById(R.id.status); instructions=findViewById(R.id.instructions); results=findViewById(R.id.results);
        check.setOnClickListener(v->checkMic());
        start.setOnClickListener(v->runVolume());
        share.setOnClickListener(v->shareJson());
    }

    void checkMic(){
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQ); return;
        }
        int min=AudioRecord.getMinBufferSize(SR,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
        try{
            AudioRecord ar=new AudioRecord(MediaRecorder.AudioSource.UNPROCESSED,SR,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min,SR));
            boolean ok=ar.getState()==AudioRecord.STATE_INITIALIZED;
            String s="Permission: GRANTED\nAudio source: UNPROCESSED\nSample rate: "+SR+" Hz\nAudioRecord: "+(ok?"READY ✓":"FAILED");
            if(ok){ ar.startRecording(); short[] x=new short[2048]; int n=ar.read(x,0,x.length); ar.stop(); s+="\nSamples read: "+n; }
            ar.release(); status.setText(s); start.setEnabled(ok);
            if(ok) instructions.setText("Ready. Connect phone MEDIA audio to Sonata. Set car volume to 20, keep phone fixed, then tap START / NEXT VOLUME.");
        }catch(Exception e){status.setText("Mic error: "+e.getClass().getSimpleName()+"\n"+e.getMessage());}
    }
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){
        super.onRequestPermissionsResult(r,p,g); if(r==REQ) checkMic();
    }

    void runVolume(){
        if(busy||vi>=VOLS.length)return; busy=true; start.setEnabled(false);
        final int vol=VOLS[vi]; instructions.setText("Measuring Sonata volume "+vol+"… Do not move the phone.");
        new Thread(()->{
            try{
                JSONObject row=new JSONObject();
                for(int f:FREQS) row.put(String.valueOf(f),measureTone(f));
                all.put(String.valueOf(vol),row); vi++;
                runOnUiThread(()->{
                    render();
                    if(vi<VOLS.length){
                        instructions.setText("Set Sonata volume to "+VOLS[vi]+", then tap START / NEXT VOLUME.");
                        start.setEnabled(true);
                    } else {
                        instructions.setText("TEST COMPLETE. Share the JSON file with ChatGPT.");
                        share.setEnabled(true);
                    }
                    busy=false;
                });
            }catch(Exception e){runOnUiThread(()->{instructions.setText("Measurement error: "+e.getMessage());busy=false;start.setEnabled(true);});}
        }).start();
    }

    double measureTone(int freq)throws Exception{
        int min=AudioRecord.getMinBufferSize(SR,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord ar=new AudioRecord(MediaRecorder.AudioSource.UNPROCESSED,SR,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min,SR*2));
        AudioTrack at=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
          .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SR).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
          .setBufferSizeInBytes(SR*2).setTransferMode(AudioTrack.MODE_STREAM).build();
        ar.startRecording(); at.play();
        int N=SR*2; short[] tone=new short[N];
        for(int i=0;i<N;i++) tone[i]=(short)(Math.sin(2*Math.PI*freq*i/SR)*5000);
        new Thread(()->at.write(tone,0,tone.length,AudioTrack.WRITE_BLOCKING)).start();
        Thread.sleep(350);
        short[] buf=new short[SR]; int n=ar.read(buf,0,buf.length,AudioRecord.READ_BLOCKING);
        ar.stop(); ar.release(); at.stop(); at.release();
        // Goertzel: stable relative amplitude at the exact test frequency.
        double w=2*Math.PI*freq/SR, c=2*Math.cos(w), q0=0,q1=0,q2=0;
        for(int i=0;i<n;i++){q0=c*q1-q2+buf[i];q2=q1;q1=q0;}
        double power=q1*q1+q2*q2-c*q1*q2;
        double amp=Math.sqrt(Math.max(power,1))/Math.max(n,1);
        return Math.round(20*Math.log10(amp)*100.0)/100.0;
    }

    void render(){
        try{
            StringBuilder sb=new StringBuilder();
            for(int i=0;i<vi;i++){
                int v=VOLS[i]; sb.append("VOL ").append(v).append("\n");
                JSONObject r=all.getJSONObject(String.valueOf(v));
                for(int f:FREQS) sb.append(f).append(" Hz: ").append(r.getDouble(String.valueOf(f))).append(" dBrel\n");
                sb.append("\n");
            }
            if(vi==VOLS.length) sb.append(analyze());
            results.setText(sb.toString());
        }catch(Exception e){}
    }
    String analyze()throws Exception{
        int lo=VOLS[0],hi=VOLS[VOLS.length-1]; JSONObject a=all.getJSONObject(""+lo),b=all.getJSONObject(""+hi);
        int[] refs={500,630,1000}, bass={63,80,100}; double ref=0,core=0;
        for(int f:refs) ref+=(b.getDouble(""+f)-a.getDouble(""+f)); ref/=refs.length;
        for(int f:bass) core+=(b.getDouble(""+f)-a.getDouble(""+f)-ref); core/=bass.length;
        String verdict=core>-2?"NONE / MINIMAL":core>-4?"MILD":core>-7?"MODERATE":"SEVERE";
        return "ANALYSIS\nReference gain: "+String.format(Locale.US,"%.2f",ref)+" dB\nCore bass relative loss: "+String.format(Locale.US,"%.2f",core)+" dB\nBass roll-off: "+verdict+"\n";
    }
    void shareJson(){
        try{
            JSONObject out=new JSONObject(); out.put("app","Sonata Bass Roll-Off Test Native V1");out.put("sample_rate",SR);
            out.put("volumes",new JSONArray(VOLS));out.put("frequencies_hz",new JSONArray(FREQS));out.put("measurements",all);out.put("analysis_text",analyze());
            File f=new File(getCacheDir(),"Sonata_BassTest.json");try(FileWriter w=new FileWriter(f)){w.write(out.toString(2));}
            // Simpler universal share: JSON as text; user can save/share to ChatGPT.
            Intent in=new Intent(Intent.ACTION_SEND);in.setType("application/json");in.putExtra(Intent.EXTRA_TEXT,out.toString(2));
            startActivity(Intent.createChooser(in,"Share Sonata Bass Test results"));
        }catch(Exception e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}
    }
}