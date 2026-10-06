package br.com.leitorpdf.reader

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import br.com.leitorpdf.data.pdf.PdfTextExtractor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

data class VoiceOption(val name:String,val label:String)

data class ReaderUiState(
    val fileName:String="",
    val uri:String="",
    val text:String="",
    val pageTexts:List<String> = emptyList(),
    val pageCount:Int=0,
    val selectedPage:Int=1,
    val isLoading:Boolean=false,
    val isSpeaking:Boolean=false,
    val speechReady:Boolean=false,
    val speechRate:Float=1f,
    val voices:List<VoiceOption> = emptyList(),
    val selectedVoice:String?=null,
    val resumeAvailable:Boolean=false,
    val resumePage:Int=1,
    val highlightText:String="",
    val cloudTtsConfigured:Boolean=false,
    val cloudTtsEndpoint:String="",
    val error:String?=null
)

class ReaderViewModel(application:Application):AndroidViewModel(application),TextToSpeech.OnInitListener{
    private val extractor=PdfTextExtractor(application)
    private val app=application
    private val prefs=application.getSharedPreferences("reading_progress",Context.MODE_PRIVATE)
    private val cloud=CloudTtsClient(application)
    private val _state=MutableStateFlow(ReaderUiState())
    val state:StateFlow<ReaderUiState> = _state.asStateFlow()
    private val tts=TextToSpeech(application,this)
    private var ttsReady=false

    init{
        tts.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
    }

    fun openPdf(uri:Uri,name:String){
        val savedUri=prefs.getString("uri",null);val lastUri=prefs.getString("last_uri",null)
        val same=savedUri==uri.toString()||lastUri==uri.toString()
        val savedPage=prefs.getInt("page",1).coerceAtLeast(1);val available=prefs.getBoolean("available",false)
        prefs.edit().putString("last_uri",uri.toString()).putString("last_name",name).apply()
        _state.value=_state.value.copy(fileName=name,uri=uri.toString(),isLoading=true,error=null,resumeAvailable=same&&available,resumePage=savedPage,highlightText=if(same)prefs.getString("highlight_text","").orEmpty() else"")
        viewModelScope.launch{
            runCatching{extractor.extractPages(uri)}.onSuccess{pages->
                val total=pages.size.coerceAtLeast(1);val start=if(same)savedPage.coerceIn(1,total) else 1
                _state.value=_state.value.copy(pageTexts=pages,pageCount=pages.size,selectedPage=start,text=pages.joinToString("\n\n").trim(),isLoading=false,error=if(pages.joinToString("").isBlank())"Este PDF parece ser escaneado. O OCR será adicionado na próxima etapa." else null)
            }.onFailure{_state.value=_state.value.copy(isLoading=false,error="Não foi possível extrair o texto deste PDF.")}
        }
    }

    fun setSelectedPage(page:Int){
        val count=_state.value.pageCount;if(count<=0)return;val safe=page.coerceIn(1,count)
        _state.value=_state.value.copy(selectedPage=safe,highlightText=if(_state.value.isSpeaking)_state.value.highlightText else"")
    }

    fun selectVoice(name:String?){
        _state.value=_state.value.copy(selectedVoice=name);prefs.edit().putString("voice",name).apply()
        if(_state.value.isSpeaking)startService(prefs.getInt("current_page",_state.value.selectedPage),prefs.getInt("current_sentence",0))
    }

    fun toggleSpeech(){
        val c=_state.value
        if(c.isSpeaking)pauseSpeech()
        else if(c.resumeAvailable&&prefs.getString("uri",null)==c.uri)continueReading()
        else startReadingFromPage(c.selectedPage)
    }

    fun startReadingFromPage(page:Int){if(_state.value.pageTexts.isEmpty())return;val p=page.coerceIn(1,_state.value.pageTexts.size);setSelectedPage(p);startService(p,0)}
    fun continueReading(){
        val c=_state.value;if(c.pageTexts.isEmpty())return
        if(prefs.getString("uri",null)!=c.uri){startReadingFromPage(c.selectedPage);return}
        val p=prefs.getInt("page",c.selectedPage).coerceIn(1,c.pageTexts.size);val s=prefs.getInt("chunk",0).coerceAtLeast(0)
        setSelectedPage(p);startService(p,s)
    }

    private fun startService(page:Int,chunk:Int){
        val c=_state.value;if(c.pageTexts.isEmpty())return
        val i=Intent(app,PdfSpeechService::class.java).apply{
            action=PdfSpeechService.ACTION_PLAY
            putStringArrayListExtra(PdfSpeechService.EXTRA_PAGES,ArrayList(c.pageTexts))
            putExtra(PdfSpeechService.EXTRA_URI,c.uri);putExtra(PdfSpeechService.EXTRA_FILE_NAME,c.fileName)
            putExtra(PdfSpeechService.EXTRA_RATE,c.speechRate);putExtra(PdfSpeechService.EXTRA_VOICE,c.selectedVoice)
            putExtra(PdfSpeechService.EXTRA_PAGE,page);putExtra(PdfSpeechService.EXTRA_CHUNK,chunk)
        }
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O)ContextCompat.startForegroundService(app,i) else app.startService(i)
        _state.value=c.copy(isSpeaking=true,error=null,selectedPage=page)
    }

    fun syncPlayback(){
        val c=_state.value;if(prefs.getString("uri",null)!=c.uri)return
        val playing=prefs.getBoolean("playing",false);val page=prefs.getInt("current_page",c.selectedPage).coerceIn(1,c.pageCount.coerceAtLeast(1))
        val h=prefs.getString("highlight_text","").orEmpty()
        val nh=if(playing)h else c.highlightText
        val speechError=prefs.getString("speech_error",null)
        if(c.isSpeaking!=playing||c.selectedPage!=page||c.highlightText!=nh||c.error!=speechError)
            _state.value=c.copy(isSpeaking=playing,selectedPage=page,highlightText=nh,
                resumeAvailable=prefs.getBoolean("available",c.resumeAvailable),error=speechError)
    }

    fun pauseSpeech(){app.startService(Intent(app,PdfSpeechService::class.java).setAction(PdfSpeechService.ACTION_PAUSE));_state.value=_state.value.copy(isSpeaking=false)}
    fun stopSpeech(){app.startService(Intent(app,PdfSpeechService::class.java).setAction(PdfSpeechService.ACTION_STOP));_state.value=_state.value.copy(isSpeaking=false,highlightText="")}

    fun setSpeechRate(rate:Float){
        val safe=rate.coerceIn(.5f,2f);_state.value=_state.value.copy(speechRate=safe);prefs.edit().putFloat("rate",safe).apply()
        if(ttsReady)tts.setSpeechRate(safe)
    }

    fun setCloudEndpoint(value:String){
        cloud.saveEndpoint(value)
        prefs.edit().remove("voice").apply()
        rebuildVoiceOptions()
    }

    fun cloudEndpoint():String=cloud.endpoint()

    private fun rebuildVoiceOptions(){
        val endpoint=cloud.endpoint()
        if(endpoint.isNotBlank()){
            val options=cloudVoices()
            val saved=prefs.getString("voice",null)
            val selected=saved?.takeIf{n->options.any{it.name==n}}?:options.first().name
            prefs.edit().putString("voice",selected).apply()
            _state.value=_state.value.copy(voices=options,selectedVoice=selected,cloudTtsConfigured=true,cloudTtsEndpoint=endpoint)
        }else if(ttsReady){
            val options=systemVoices()
            val saved=prefs.getString("voice",null)
            val selected=saved?.takeIf{n->options.any{it.name==n}}?:options.firstOrNull()?.name
            _state.value=_state.value.copy(voices=options,selectedVoice=selected,cloudTtsConfigured=false,cloudTtsEndpoint="")
        }
    }

    private fun cloudVoices()=listOf(
        VoiceOption("pt-BR-Chirp3-HD-Charon","Masculina • grave e documental"),
        VoiceOption("pt-BR-Chirp3-HD-Enceladus","Masculina • quente e natural"),
        VoiceOption("pt-BR-Chirp3-HD-Iapetus","Masculina • firme e clara"),
        VoiceOption("pt-BR-Chirp3-HD-Orus","Masculina • suave e profissional"),
        VoiceOption("pt-BR-Chirp3-HD-Rasalgethi","Masculina • encorpada e narrativa"),
        VoiceOption("pt-BR-Chirp3-HD-Sadachbia","Masculina • expressiva e natural"),
        VoiceOption("pt-BR-Chirp3-HD-Sadaltager","Masculina • segura e elegante"),
        VoiceOption("pt-BR-Chirp3-HD-Schedar","Masculina • equilibrada e didática"),
        VoiceOption("pt-BR-Chirp3-HD-Fenrir","Masculina • energética e marcante"),
        VoiceOption("pt-BR-Chirp3-HD-Achird","Masculina • conversacional e natural"),
        VoiceOption("pt-BR-Chirp3-HD-Zubenelgenubi","Masculina • casual e descontraída"),
        VoiceOption("pt-BR-Chirp3-HD-Achernar","Feminina • clara e profissional"),
        VoiceOption("pt-BR-Chirp3-HD-Gacrux","Feminina • natural e acolhedora"),
        VoiceOption("pt-BR-Chirp3-HD-Kore","Feminina • firme e elegante")
    )

    private fun systemVoices():List<VoiceOption> = tts.voices.orEmpty().filter{it.locale.language=="pt"}.distinctBy{it.name}
        .sortedWith(compareByDescending<Voice>{it.locale.toLanguageTag().equals("pt-BR",true)}.thenByDescending{it.quality}.thenBy{it.latency}.thenBy{it.isNetworkConnectionRequired}.thenBy{it.name})
        .map{VoiceOption(it.name,voiceLabel(it))}

    override fun onInit(status:Int){
        if(status!=TextToSpeech.SUCCESS){ttsReady=false;_state.value=_state.value.copy(speechReady=false,error="Não foi possível iniciar o mecanismo de voz do Android.");return}
        ttsReady=true
        val savedRate=prefs.getFloat("rate",1f).coerceIn(.5f,2f)
        _state.value=_state.value.copy(speechReady=true,speechRate=savedRate,error=null)
        tts.setSpeechRate(savedRate)
        rebuildVoiceOptions()
    }

    private fun voiceLabel(v:Voice):String{
        val lang=v.locale.displayLanguage.replaceFirstChar{it.uppercase()};val country=v.locale.displayCountry
        val quality=if(v.quality>=Voice.QUALITY_HIGH)"Alta qualidade" else"Padrão"
        val connection=if(v.isNetworkConnectionRequired)" • online" else" • instalada"
        return if(country.isBlank())"$lang • $quality • ${v.name}$connection" else"$lang ($country) • $quality • ${v.name}$connection"
    }

    override fun onCleared(){tts.shutdown();super.onCleared()}
}
