package app.tyumex.smartdns

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

object Installer {
    private fun digest(b:ByteArray)=MessageDigest.getInstance("SHA-256").digest(b).joinToString(""){"%02x".format(it)}
    fun download(a:Activity,tag:String){
        require(tag.matches(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+")))
        val base=repo+"/releases/download/"+tag+"/"
        val expected=String(bytes(base+"SHA256SUMS",1024)).lineSequence()
            .map{it.trim().split(Regex("\\s+"))}.first{it.size==2&&it[1]=="Tyumex-Smart-DNS.apk"}[0]
        val dir=File(a.cacheDir,"updates").apply{mkdirs()}
        val tmp=File.createTempFile("incoming-", ".apk",dir)
        try{
            val hash=MessageDigest.getInstance("SHA-256")
            val conn=java.net.URL(base+"Tyumex-Smart-DNS.apk").openConnection() as java.net.HttpURLConnection
            conn.connectTimeout=10000;conn.readTimeout=20000;conn.setRequestProperty("User-Agent",agent)
            try{require(conn.responseCode==200){"HTTP ${conn.responseCode}"}
                conn.inputStream.use{input->tmp.outputStream().use{out->
                    val buf=ByteArray(32768);var total=0L
                    while(true){val n=input.read(buf);if(n<0)break;total+=n;require(total<=64L*1024*1024)
                        hash.update(buf,0,n);out.write(buf,0,n)}
                }}
            }finally{conn.disconnect()}
            require(hash.digest().joinToString(""){"%02x".format(it)}.equals(expected,true)){"Не совпала контрольная сумма APK"}
            val pm=a.packageManager
            @Suppress("DEPRECATION")
            val flags=if(Build.VERSION.SDK_INT>=28)PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
            val current=pm.getPackageInfo(a.packageName,flags)
            val next=pm.getPackageArchiveInfo(tmp.path,flags)?:error("Неверный формат APK")
            @Suppress("DEPRECATION")
            fun sign(i:android.content.pm.PackageInfo)=
                (if(Build.VERSION.SDK_INT>=28)i.signingInfo?.apkContentsSigners else i.signatures)?.map{digest(it.toByteArray())}?.toSet().orEmpty()
            require(next.packageName==a.packageName && next.versionName==tag.removePrefix("v") && sign(current).isNotEmpty() && sign(next)==sign(current)){"Неверная версия или подпись APK"}
            @Suppress("DEPRECATION")
            require(next.versionCode>current.versionCode){"Установлена такая же или более новая версия"}
            val file=File(dir,"verified.apk")
            android.system.Os.rename(tmp.path,file.path)
        }finally{tmp.delete()}
    }
    fun open(a:Activity){
        if(!a.packageManager.canRequestPackageInstalls()){
            a.startActivityForResult(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+a.packageName)),72);return
        }
        val f=File(a.cacheDir,"updates/verified.apk");require(f.isFile){"Загрузите APK ещё раз"}
        val uri=FileProvider.getUriForFile(a,a.packageName+".updates",f)
        a.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}
