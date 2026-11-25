package app.tyumex.smartdns

import android.os.SystemClock
import okhttp3.*
import java.io.*
import java.net.*
import java.security.SecureRandom
import java.util.concurrent.*

object Probe {
    val sites=linkedMapOf("chatgpt.com" to "ChatGPT","claude.ai" to "Claude","gemini.google.com" to "Gemini","elevenlabs.io" to "ElevenLabs","grok.com" to "Grok")
    private val random=SecureRandom()
    private fun skip(b:ByteArray,from:Int):Int{
        var p=from
        while(p<b.size){val n=b[p++].toInt() and 255
            if(n==0)return p
            if(n and 192==192){require(p<b.size);return p+1}
            require(n<=63);p+=n
        };error("DNS name")
    }
    private fun word(b:ByteArray,p:Int)=((b[p].toInt() and 255) shl 8) or (b[p+1].toInt() and 255)
    fun resolve(ip:String,host:String):List<InetAddress>{
        val id=random.nextInt(65536);val buffer=ByteArrayOutputStream()
        DataOutputStream(buffer).apply{writeShort(id);writeShort(256);writeShort(1);repeat(3){writeShort(0)}
            host.split('.').forEach{writeByte(it.length);writeBytes(it)};writeByte(0);writeShort(1);writeShort(1)}
        val q=buffer.toByteArray()
        return DatagramSocket().use{s->s.soTimeout=1400;s.connect(InetSocketAddress(ip,53));s.send(DatagramPacket(q,q.size))
            val pkt=DatagramPacket(ByteArray(4096),4096);s.receive(pkt);val b=pkt.data.copyOf(pkt.length)
            require(b.size>=12 && word(b,0)==id && word(b,2) and 0x800f==0x8000 && word(b,4)==1)
            var p=skip(b,12)+4;val out=mutableListOf<InetAddress>()
            repeat(word(b,6).coerceAtMost(64)){p=skip(b,p);val kind=word(b,p);val cls=word(b,p+2);val len=word(b,p+8);p+=10;require(p+len<=b.size)
                if(kind==1&&cls==1&&len==4){val a=InetAddress.getByAddress(b.copyOfRange(p,p+4))
                    if(!a.isAnyLocalAddress&&!a.isSiteLocalAddress&&!a.isLoopbackAddress&&!a.isLinkLocalAddress&&!a.isMulticastAddress)out.add(a)}
                p+=len};out
        }
    }
    data class Score(val provider:Provider,val good:Int,val ms:Long,val hits:List<String>)
    private val pool=Executors.newFixedThreadPool(12)
    private fun test(p:Provider,host:String):Long?{
        val start=SystemClock.elapsedRealtime()
        val addresses=p.ips.asSequence().map{runCatching{resolve(it,host)}.getOrDefault(emptyList())}.firstOrNull{it.isNotEmpty()}?:return null
        val client=OkHttpClient.Builder().proxy(Proxy.NO_PROXY).dns(object:Dns{
            override fun lookup(hostname:String)=if(hostname==host)addresses else throw UnknownHostException(hostname)
        }).connectTimeout(2,TimeUnit.SECONDS).readTimeout(2,TimeUnit.SECONDS).callTimeout(4,TimeUnit.SECONDS)
          .followRedirects(false).build()
        try{client.newCall(Request.Builder().url("https://"+host+"/").header("User-Agent",agent).build()).execute().use{r->
            if(r.code !in 200..399)return null
            val body=(r.peekBody(8192).string()+" "+r.header("Location","")).lowercase()
            if(listOf("unsupported_country","not available in your region","not available in your country","/sorry/","cf-chl-","error code: 1009","just a moment...").any{it in body})return null
            return SystemClock.elapsedRealtime()-start}
        }finally{client.connectionPool.evictAll()}
    }
    fun rank(providers:List<Provider>,hosts:Set<String>):List<Score>{
        val tasks=providers.associateWith{p->hosts.associateWith{h->pool.submit<Long?>{runCatching{test(p,h)}.getOrNull()}}}
        return tasks.map{(p,tests)->val ok=tests.mapNotNull{(h,f)->f.get()?.let{h to it}}
            Score(p,ok.size,ok.map{it.second}.sorted().let{if(it.isEmpty())Long.MAX_VALUE else it[it.size/2]},ok.map{it.first})}
            .sortedWith(compareByDescending<Score>{it.good}.thenBy{it.ms})
    }
}
