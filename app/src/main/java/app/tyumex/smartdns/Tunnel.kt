package app.tyumex.smartdns

import android.app.*
import android.content.*
import android.net.*
import android.os.*
import android.system.*
import android.service.quicksettings.TileService
import java.io.*
import java.util.concurrent.Executors
import kotlin.concurrent.thread

private class Bridge(val fd:ParcelFileDescriptor,val child:java.lang.Process,val died:()->Unit){
    @Volatile private var closed=false
    fun start(){
        fun worker(body:()->Unit)=thread(isDaemon=true){try{body()}catch(_:Exception){}finally{if(!closed)died()}}
        worker{
            val out=DataOutputStream(BufferedOutputStream(child.outputStream));val buf=ByteArray(65535)
            val poll=StructPollfd().apply{fd=this@Bridge.fd.fileDescriptor;events=OsConstants.POLLIN.toShort()}
            while(!closed){if(Os.poll(arrayOf(poll),400)==0)continue
                val n=Os.read(fd.fileDescriptor,buf,0,buf.size);if(n<=0)break
                out.writeShort(n);out.write(buf,0,n);out.flush()}
        }
        worker{
            val input=DataInputStream(BufferedInputStream(child.inputStream));val b=ByteArray(65535)
            while(!closed){val n=input.readUnsignedShort();require(n in 1..65535);input.readFully(b,0,n);Os.write(fd.fileDescriptor,b,0,n)}
        }
    }
    fun close(){closed=true;child.destroy();runCatching{fd.close()}}
}
class Tunnel:VpnService(),SharedPreferences.OnSharedPreferenceChangeListener{
    companion object{
        @Volatile var running=false
        const val STOP="stop"
        fun toggleOn(c:Context){c.startForegroundService(Intent(c,Tunnel::class.java))}
    }
    private val handler=Handler(Looper.getMainLooper())
    private val executor=Executors.newSingleThreadExecutor()
    private var alive=true
    private var current:Provider?=null
    private var bridge:Bridge?=null
    private var ranking=false
    private var pending=false
    private var networkChanged=false
    private var activeNetwork:Network?=null
    private val timer=object:Runnable{override fun run(){if(running){select();handler.postDelayed(this,15*60*1000L)}}}
    private val onNetwork=object:ConnectivityManager.NetworkCallback(){
        override fun onAvailable(n:Network){handler.post{if(n!=activeNetwork){activeNetwork=n;networkChanged=true;select()}}}
        override fun onLost(n:Network){handler.post{if(activeNetwork==n){activeNetwork=null;state("Ожидаю подключения к сети")}}}
    }
    private fun state(msg:String){
        prefs.edit().putString("state",msg).apply()
        if(running)getSystemService(NotificationManager::class.java).notify(53,note(msg))
        TileService.requestListeningState(this,ComponentName(this,QuickTile::class.java))
    }
    private fun note(msg:String):Notification{
        val open=PendingIntent.getActivity(this,0,Intent(this,Main::class.java),PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,1,Intent(this,Tunnel::class.java).setAction(STOP),PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this,"tunnel").setSmallIcon(R.drawable.ic_dns).setContentTitle("Tyumex Smart DNS")
            .setContentText(msg).setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null,"Отключить",stop).build()).build()
    }
    override fun onCreate(){super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("tunnel","Соединение DNS",NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(i:Intent?,flags:Int,id:Int):Int{
        if(i?.action==STOP){disconnect();return START_NOT_STICKY}
        if(!running){running=true;startForeground(53,note("Выбор DNS…"));prefs.registerOnSharedPreferenceChangeListener(this)
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(onNetwork)
            handler.postDelayed(timer,15*60*1000L)}
        select();return START_NOT_STICKY
    }
    override fun onSharedPreferenceChanged(p:SharedPreferences?,k:String?){if(k in listOf("signed","mode","hosts"))select()}
    private fun select(){
        if(!alive || !running)return
        if(ranking){pending=true;return}
        ranking=true
        val cat=Store.current(this)
        val manual=cat.providers.firstOrNull{it.id==prefs.getString("mode","auto")}
        if(manual!=null){try{activate(manual)}catch(e:Exception){fail(e)}finally{ranking=false};return}
        val hosts=prefs.getStringSet("hosts",setOf("chatgpt.com","claude.ai"))!!.intersect(Probe.sites.keys).ifEmpty{setOf("chatgpt.com")}
        val netChanged=networkChanged;networkChanged=false
        state(if(current==null)"Проверяю доступность сервисов…" else "${current!!.name} · проверяю сервисы…")
        executor.submit{val scores=runCatching{Probe.rank(cat.providers.filter{it.id!="xbox"},hosts)}
            handler.post{ranking=false;if(!alive || !running)return@post
                if(pending){pending=false;select();return@post}
                val all=scores.getOrDefault(emptyList());val top=all.firstOrNull()
                prefs.edit().putString("scores",all.joinToString("\n"){s->
                    "${s.provider.name} · ${s.good}/${hosts.size}"+if(s.good==0)"" else " · ${s.ms} мс · "+s.hits.joinToString{Probe.sites[it]?:it}
                }).apply()
                try{if(top==null||top.good==0){
                    if(current==null||cat.providers.none{it==current}){fail(IllegalStateException("Ни один DNS не прошёл проверку. Попробуйте другой профиль или ручной выбор."))}
                    else state("${current!!.name} · проверка сайтов не прошла")
                }else{
                    val prev=all.firstOrNull{it.provider.id==current?.id}
                    val keep=!netChanged && prev!=null && prev.good==top.good && prev.ms<=top.ms+250
                    activate(if(keep)prev!!.provider else top.provider)
                }}catch(e:Exception){fail(e)}
            }}
    }
    private fun activate(p:Provider){
        if(current==p && bridge!=null){state("Включено · ${p.name}");return}
        val exe=File(applicationInfo.nativeLibraryDir,"libsmartdns.so")
        val child=ProcessBuilder(listOf(exe.absolutePath)+p.ips).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        try{
            val tun=Builder().setSession("Tyumex · "+p.name).setMtu(1500)
                .addAddress("192.0.2.1",32).addAddress("fd42:53::1",128)
                .addRoute("192.0.2.53",32).addRoute("fd42:53::53",128)
                .addDnsServer("192.0.2.53").addDnsServer("fd42:53::53")
                .addDisallowedApplication(packageName).establish()?:error("Разрешение VPN отозвано")
            val old=bridge
            val next=Bridge(tun,child){handler.post{if(bridge?.child===child && running)fail(IOException("Соединение DNS прервано"))}}
            bridge=next;current=p;old?.close();next.start();state("Включено · ${p.name}")
        }catch(e:Exception){child.destroy();throw e}
    }
    private fun fail(e:Exception){prefs.edit().putString("error",e.localizedMessage?:"Ошибка DNS").apply();disconnect()}
    private fun disconnect(){
        alive=false;running=false;val old=bridge;bridge=null;current=null;old?.close()
        handler.removeCallbacksAndMessages(null);state("Выключено");stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
    }
    override fun onRevoke(){handler.post{disconnect()}}
    override fun onDestroy(){alive=false;running=false
        runCatching{getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(onNetwork)}
        prefs.unregisterOnSharedPreferenceChangeListener(this);handler.removeCallbacksAndMessages(null)
        bridge?.close();bridge=null;executor.shutdownNow();state("Выключено");stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()}
}
