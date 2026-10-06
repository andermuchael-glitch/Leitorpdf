package br.com.leitorpdf.reader

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.*
import android.net.Uri
import android.os.*
import android.speech.tts.*
import androidx.core.app.*
import kotlinx.coroutines.*
import java.util.Locale

class PdfSpeechService : Service(), TextToSpeech.OnInitListener {
    companion object {
        const val ACTION_PLAY="br.com.leitorpdf.PLAY"
        const val ACTION_PAUSE="br.com.leitorpdf.PAUSE"
        const val ACTION_STOP="br.com.leitorpdf.STOP"
        const val EXTRA_PAGES="pages"
        const val EXTRA_URI="uri"
        const val EXTRA_FILE_NAME="fileName"
        const val EXTRA_RATE="rate"
        const val EXTRA_VOICE="voice"
        const val EXTRA_PAGE="page"
        const val EXTRA_CHUNK="chunk"
        private const val CHANNEL="pdf_reading"
        private const val ID=365
        private const val PREFS="reading_progress"
    }

    private lateinit var tts:TextToSpeech
    private lateinit var cloud:CloudTtsClient
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val prefs by lazy{getSharedPreferences(PREFS,MODE_PRIVATE)}
    private var pages=emptyList<String>()
    private var page=1
    private var sentence=0
    private var rate=1f
    private var voice:String?=null
    private var uri=""
    private var fileName="PDF"
    private var paused=false
    private var player:MediaPlayer?=null
    private var generation=0L
    private var systemFallback=false
    private var ready=false

    override fun onCreate(){
        super.onCreate()
        cloud=CloudTtsClient(this)
        channel()
        tts=TextToSpeech(this,this)
        tts.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        tts.setOnUtteranceProgressListener(object:UtteranceProgressListener(){
            override fun onStart(id:String?){if(!isId(id))return;pos(id)?.let{started(it.first,it.second)}}
            override fun onDone(id:String?){if(!isId(id)||paused)return;pos(id)?.let{finished(it.first,it.second)}}
            @Suppress("DEPRECATION") override fun onError(id:String?){if(isId(id)){paused=true;save(false);notify(false)}}
        })
    }

    override fun onStartCommand(i:Intent?,flags:Int,startId:Int):Int{
        when(i?.action){
            ACTION_PAUSE->pause()
            ACTION_STOP->stop()
            ACTION_PLAY->{
                i.getStringArrayListExtra(EXTRA_PAGES)?.takeIf{it.isNotEmpty()}?.let{
                    pages=it
                    uri=i.getStringExtra(EXTRA_URI).orEmpty()
                    fileName=i.getStringExtra(EXTRA_FILE_NAME)?:"PDF"
                    rate=i.getFloatExtra(EXTRA_RATE,1f).coerceIn(.5f,2f)
                    voice=i.getStringExtra(EXTRA_VOICE)
                    page=i.getIntExtra(EXTRA_PAGE,1).coerceIn(1,pages.size)
                    sentence=i.getIntExtra(EXTRA_CHUNK,0).coerceAtLeast(0)
                }
                paused=false
                foreground()
                if(ready)start()
            }
        }
        return START_STICKY
    }

    private fun start(){
        val v=voice?.takeIf{it.startsWith("pt-BR-Chirp3-HD-")}
        if(v!=null&&cloud.isConfigured()){systemFallback=false;cloudSegment(v)}else{systemFallback=true;systemSegment()}
    }

    private fun cloudSegment(v:String){
        val p=normalize()?:return
        val g=++generation
        scope.launch{
            val f=withContext(Dispatchers.IO){
                val text=parts(pages[p.first-1]).getOrNull(p.second)?.first
                text?.let{cloud.synthesize(it,v,rate)}
            }
            if(g!=generation||paused)return@launch
            if(f==null){systemFallback=true;systemSegment();return@launch}
            started(p.first,p.second)
            play(f,g,p)
            prefetch(p,v)
        }
    }

    private fun prefetch(cur:Pair<Int,Int>,v:String){
        val next=generateSequence(advance(cur.first,cur.second)){advance(it.first,it.second)}.take(3).toList()
        scope.launch(Dispatchers.IO){
            next.map{async{
                parts(pages[it.first-1]).getOrNull(it.second)?.first?.let{txt->cloud.synthesize(txt,v,rate)}
            }}.awaitAll()
        }
    }

    private fun play(file:java.io.File,g:Long,p:Pair<Int,Int>){
        release()
        val m=MediaPlayer()
        player=m
        m.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        m.setDataSource(this,Uri.fromFile(file))
        m.setOnPreparedListener{
            if(g!=generation||paused){release();return@setOnPreparedListener}
            m.start()
            started(p.first,p.second)
            save(true)
            notify(true)
        }
        m.setOnCompletionListener{
            if(g!=generation||paused)return@setOnCompletionListener
            release()
            finished(p.first,p.second)
        }
        m.setOnErrorListener{_,_,_->
            if(g==generation&&!paused){systemFallback=true;systemSegment()}
            release()
            true
        }
        try{m.prepareAsync()}catch(_:Throwable){release();systemFallback=true;systemSegment()}
    }

    private fun systemSegment(){
        if(!ready||paused)return
        val p=normalize()?:return
        configureSystem()
        val text=parts(pages[p.first-1]).getOrNull(p.second)?.first?:return
        tts.stop()
        tts.speak(text,TextToSpeech.QUEUE_FLUSH,null,id(p.first,p.second))
        save(true)
        notify(true)
    }

    private fun configureSystem(){
        val wanted=voice?.let{n->tts.voices?.firstOrNull{it.name==n}}
        if(wanted!=null){tts.setLanguage(wanted.locale);tts.voice=wanted}else tts.setLanguage(Locale("pt","BR"))
        tts.setSpeechRate(rate)
        tts.setPitch(.98f)
    }

    private fun started(p:Int,s:Int){
        page=p
        sentence=s
        val original=parts(pages.getOrNull(p-1).orEmpty()).getOrNull(s)?.second.orEmpty()
        prefs.edit().putInt("current_page",p).putInt("current_sentence",s).putString("highlight_text",original).putBoolean("playing",true).putBoolean("available",true).apply()
        save(true)
        notify(true)
    }

    private fun finished(p:Int,s:Int){
        if(paused)return
        val n=advance(p,s)
        if(n==null){
            paused=true
            prefs.edit().putBoolean("available",false).putBoolean("playing",false).apply()
            notify(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        page=n.first
        sentence=n.second
        save(true)
        if(systemFallback)systemSegment()
        else voice?.takeIf{it.startsWith("pt-BR-Chirp3-HD-")&&cloud.isConfigured()}?.let{cloudSegment(it)}
            ?:run{systemFallback=true;systemSegment()}
    }

    private fun normalize():Pair<Int,Int>?{
        var p=page.coerceIn(1,pages.size)
        var s=sentence.coerceAtLeast(0)
        while(p<=pages.size){
            val ps=parts(pages[p-1])
            if(s<ps.size){page=p;sentence=s;return p to s}
            p++
            s=0
        }
        return null
    }

    private fun advance(p:Int,s:Int):Pair<Int,Int>?{
        val ps=parts(pages.getOrNull(p-1).orEmpty())
        if(s+1<ps.size)return p to s+1
        return if(p+1<=pages.size)p+1 to 0 else null
    }

    private fun parts(text:String):List<Pair<String,String>>{
        val n=text.replace("\r\n","\n").replace("\r","\n").trim()
        if(n.isBlank())return emptyList()
        return n.split(Regex("(?<=[.!?…])\\s+|\\n{2,}"))
            .map{it.trim()}.filter{it.isNotBlank()}.map{it to it}
    }

    private fun id(p:Int,s:Int)="pdf-reader-$p-$s"
    private fun isId(v:String?)=v?.startsWith("pdf-reader-")==true
    private fun pos(v:String?):Pair<Int,Int>?{
        val m=Regex("^pdf-reader-(\\d+)-(\\d+)$").find(v?:return null)?:return null
        return m.groupValues[1].toIntOrNull()?.let{p->m.groupValues[2].toIntOrNull()?.let{s->p to s}}
    }

    private fun pause(){paused=true;generation++;tts.stop();release();save(false);notify(false)}
    private fun stop(){paused=true;generation++;tts.stop();release();save(false);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}

    private fun release(){
        player?.let{try{it.stop()}catch(_:Throwable){};it.reset();it.release()}
        player=null
    }

    private fun save(playing:Boolean){
        if(uri.isBlank())return
        prefs.edit().putString("uri",uri).putInt("page",page).putInt("chunk",sentence)
            .putInt("current_page",page).putInt("current_sentence",sentence)
            .putFloat("rate",rate).putString("voice",voice)
            .putBoolean("available",true).putBoolean("playing",playing).apply()
    }

    private fun foreground(){
        val n=build(true)
        if(Build.VERSION.SDK_INT>=29)ServiceCompat.startForeground(this,ID,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else ServiceCompat.startForeground(this,ID,n,0)
    }

    private fun notify(playing:Boolean){getSystemService(NotificationManager::class.java).notify(ID,build(playing))}

    private fun build(playing:Boolean):Notification{
        val toggle=PendingIntent.getService(this,1,Intent(this,PdfSpeechService::class.java).setAction(if(playing)ACTION_PAUSE else ACTION_PLAY),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,2,Intent(this,PdfSpeechService::class.java).setAction(ACTION_STOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Leitor PDF • "+if(playing){if(systemFallback)"Voz do aparelho" else "Narrador profissional"}else"Pausado")
            .setContentText("$fileName • página $page de ${pages.size}")
            .setOngoing(playing).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(if(playing)android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,if(playing)"Pausar" else"Continuar",toggle)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,"Parar",stop).build()
    }

    private fun channel(){getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"Leitura de PDF",NotificationManager.IMPORTANCE_LOW))}
    override fun onInit(status:Int){if(status==TextToSpeech.SUCCESS){ready=true;if(pages.isNotEmpty()&&!paused)start()}}
    override fun onDestroy(){generation++;scope.cancel();release();tts.stop();tts.shutdown();super.onDestroy()}
    override fun onBind(i:Intent?):IBinder?=null
}
