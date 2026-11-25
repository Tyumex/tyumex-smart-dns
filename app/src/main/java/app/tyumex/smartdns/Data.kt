package app.tyumex.smartdns

import android.app.job.*
import android.content.*
import android.net.InetAddresses
import android.os.Build
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.net.*
import java.security.*
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.Executors

const val repo = "https://github.com/Tyumex/tyumex-smart-dns"
const val agent = "Tyumex-Smart-DNS/1.0"
data class Provider(val id:String,val name:String,val ips:List<String>)
data class Catalog(val rev:Int,val source:String,val providers:List<Provider>)
val Context.prefs get() = getSharedPreferences("dns",Context.MODE_PRIVATE)
fun bytes(url:String, limit:Int):ByteArray {
    val c=URL(url).openConnection() as HttpURLConnection
    c.connectTimeout=5000; c.readTimeout=10000; c.setRequestProperty("User-Agent",agent)
    try { require(c.responseCode==200){"HTTP ${c.responseCode}"}
        return c.inputStream.use { input -> val out=java.io.ByteArrayOutputStream(); val buf=ByteArray(8192)
            while(true){val n=input.read(buf);if(n<0)break;require(out.size()+n<=limit);out.write(buf,0,n)};out.toByteArray() }
    } finally { c.disconnect() }
}
object Store {
    private fun parse(ctx:Context,data:ByteArray,sig:ByteArray):Catalog {
        val key=KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(ctx.assets.open("catalog.der").use{it.readBytes()}))
        require(Signature.getInstance("SHA256withRSA").run{initVerify(key);update(data);verify(sig)}){"Подпись каталога недействительна"}
        val j=JSONObject(String(data,Charsets.UTF_8)); val a=j.getJSONArray("providers");require(a.length() in 1..32)
        val providers=(0 until a.length()).map{val p=a.getJSONObject(it);val ips=p.getJSONArray("servers");require(ips.length() in 1..8)
            Provider(p.getString("id"),p.getString("name"),(0 until ips.length()).map{k->ips.getString(k).also { ip->
                require(ip.matches(Regex("[0-9A-Fa-f:.]+")) && (ip.contains(':') || ip.count{it=='.'}==3))
                val addr=InetAddress.getByName(ip);require(!addr.isLoopbackAddress&&!addr.isAnyLocalAddress&&!addr.isMulticastAddress&&!addr.isSiteLocalAddress&&!addr.isLinkLocalAddress)
            }})}
        require(providers.distinctBy{it.id}.size==providers.size)
        return Catalog(j.getInt("revision"),j.getString("upstream"),providers)
    }
    fun current(ctx:Context):Catalog{
        val bundled=parse(ctx,ctx.assets.open("servers.json").use{it.readBytes()},ctx.assets.open("servers.sig").use{it.readBytes()})
        return runCatching {val raw=ctx.prefs.getString("signed",null)!!.split(".")
            parse(ctx,Base64.decode(raw[0],0),Base64.decode(raw[1],0)).takeIf{it.rev>=bundled.rev}?:bundled
        }.getOrDefault(bundled)
    }
    @Synchronized fun refresh(ctx:Context){
        val base="https://raw.githubusercontent.com/Tyumex/tyumex-smart-dns/main/catalog/"
        val data=bytes(base+"servers.json",65536);val sig=bytes(base+"servers.sig",2048)
        val c=parse(ctx,data,sig);val old=current(ctx)
        if(c.rev>old.rev){
            val added=c.providers.flatMap{it.ips}.toSet()-old.providers.flatMap{it.ips}.toSet()
            val removed=old.providers.flatMap{it.ips}.toSet()-c.providers.flatMap{it.ips}.toSet()
            ctx.prefs.edit().putString("catalogChange","Адресов добавлено: ${added.size}, удалено: ${removed.size}")
                .putString("signed",Base64.encodeToString(data,2)+"."+Base64.encodeToString(sig,2)).apply()
        }
        val r=JSONObject(String(bytes("https://api.github.com/repos/Tyumex/tyumex-smart-dns/releases/latest",120000)))
        val tag=r.getString("tag_name");require(tag.matches(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+")))
        ctx.prefs.edit().putString("latest",tag).putLong("checked",System.currentTimeMillis()).apply()
    }
    fun newer(tag:String, version:String):Boolean {
        val a=tag.removePrefix("v").split(".").map{it.toIntOrNull()?:0}
        val b=version.split(".").map{it.toIntOrNull()?:0}
        for(i in 0 until maxOf(a.size,b.size)){val d=a.getOrElse(i){0}.compareTo(b.getOrElse(i){0});if(d!=0)return d>0}
        return false
    }
}
class RefreshJob:JobService(){
    private var task:java.util.concurrent.Future<*>?=null
    override fun onStartJob(p:JobParameters):Boolean{task=pool.submit{val retry=runCatching{Store.refresh(this)}.isFailure;jobFinished(p,retry)};return true}
    override fun onStopJob(p:JobParameters):Boolean{task?.cancel(true);return true}
    companion object{
        val pool=Executors.newSingleThreadExecutor()
        fun schedule(c:Context){val j=c.getSystemService(JobScheduler::class.java);if(j.getPendingJob(53)==null)
            j.schedule(JobInfo.Builder(53,ComponentName(c,RefreshJob::class.java)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(12*60*60*1000L).build())}
    }
}
