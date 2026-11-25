package app.tyumex.smartdns

import android.app.*
import android.content.*
import android.graphics.Color
import android.net.*
import android.os.Bundle
import android.provider.Settings
import android.service.quicksettings.*
import android.view.*
import android.widget.*
import android.graphics.Typeface
import android.content.res.ColorStateList
import kotlin.concurrent.thread

class Main:Activity(),SharedPreferences.OnSharedPreferenceChangeListener{
    private lateinit var status:TextView;private lateinit var summary:TextView;private lateinit var report:TextView
    private lateinit var start:Button;private lateinit var check:Button;private lateinit var update:Button;private lateinit var mode:Button;private lateinit var host:Button
    private var checking=false
    private var downloading=false
    private val mint=Color.rgb(105,226,193)
    private fun dp(v:Int)=(resources.displayMetrics.density*v).toInt()
    override fun onCreate(s:Bundle?){super.onCreate(s)
        val sc=ScrollView(this);sc.setBackgroundColor(Color.rgb(18,26,32));val box=LinearLayout(this);box.orientation=1
        box.setPadding(dp(22),dp(18),dp(22),dp(24));sc.addView(box);setContentView(sc)
        sc.setOnApplyWindowInsetsListener{v,i->v.setPadding(0,i.systemWindowInsetTop,0,i.systemWindowInsetBottom);i}
        fun label(txt:String,size:Int,color:Int=Color.WHITE):TextView{val t=TextView(this);t.text=txt;t.textSize=size.toFloat();t.setTextColor(color);t.setPadding(0,dp(9),0,dp(7));box.addView(t);return t}
        fun button(fn:()->Unit):Button{val b=Button(this);b.isAllCaps=false;b.setOnClickListener{fn()};box.addView(b,LinearLayout.LayoutParams(-1,-2));return b}
        label("Tyumex Smart DNS",30).typeface=Typeface.DEFAULT_BOLD
        status=label("",19,mint)
        start=button{if(Tunnel.running)startService(Intent(this,Tunnel::class.java).setAction(Tunnel.STOP))else consent()}
        start.backgroundTintList=ColorStateList.valueOf(mint);start.setTextColor(Color.BLACK)
        label("Подключение",21)
        mode=button{val all=Store.current(this).providers;val names=listOf("Автоматически")+all.map{it.name}
            AlertDialog.Builder(this).setTitle("Провайдер DNS").setItems(names.toTypedArray()){_,i->
                prefs.edit().putString("mode",if(i==0)"auto" else all[i-1].id).apply()}.show()}
        host=button{val entries=Probe.sites.entries.toList();val selected=(prefs.getStringSet("hosts",setOf("chatgpt.com","claude.ai"))?:emptySet()).toMutableSet()
            AlertDialog.Builder(this).setTitle("Какие сайты проверять").setMultiChoiceItems(entries.map{it.value}.toTypedArray(),entries.map{it.key in selected}.toBooleanArray()){_,i,on->if(on)selected.add(entries[i].key)else selected.remove(entries[i].key)}
                .setPositiveButton("Сохранить"){_,_->if(selected.isNotEmpty())prefs.edit().putStringSet("hosts",selected).apply()else toast("Выберите хотя бы один сайт")}.setNegativeButton("Отмена",null).show()}
        report=label("",15)
        label("Обновления",21);summary=label("",15)
        check=button{refresh()};check.text="Проверить GitHub"
        update=button{if(!downloading){downloading=true;draw();val tag=prefs.getString("latest","")!!
            thread{val result=runCatching{Installer.download(this,tag)};runOnUiThread{if(!isDestroyed){downloading=false;draw()
                result.onSuccess{runCatching{Installer.open(this)}.onFailure{toast(it.localizedMessage?:"Ошибка установки")}}
                    .onFailure{toast(it.localizedMessage?:"Ошибка загрузки")}}}}}}
        label("Работает через локальный VPN на телефоне. Другой VPN в этом профиле будет отключён. Smart DNS видит запрашиваемые домены. Браузеры со своим защищённым DNS могут игнорировать настройку.",14,Color.LTGRAY)
        RefreshJob.schedule(this)
        if(intent.getBooleanExtra("enable",false)&&s==null)consent()
        if(System.currentTimeMillis()-prefs.getLong("checked",0)>12*60*60*1000L)refresh(false)
    }
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_LONG).show()
    private fun refresh(show:Boolean=true){if(checking)return;checking=true;draw();thread{
        val result=runCatching{Store.refresh(this)};runOnUiThread{if(!isDestroyed){checking=false;draw();if(show)toast(result.exceptionOrNull()?.let{"Ошибка GitHub: "+it.localizedMessage}?:"Каталог и версия проверены")}}
    }}
    private fun consent(){prefs.edit().remove("error").apply();val ask=VpnService.prepare(this)
        if(ask==null)Tunnel.toggleOn(this)else startActivityForResult(ask,53)}
    @Deprecated("Android result callback") override fun onActivityResult(r:Int,result:Int,data:Intent?){super.onActivityResult(r,result,data);if(r==53&&result==RESULT_OK)Tunnel.toggleOn(this);if(r==72 && packageManager.canRequestPackageInstalls())runCatching{Installer.open(this)}.onFailure{toast(it.localizedMessage?:"Ошибка установки")}}
    override fun onResume(){super.onResume();prefs.registerOnSharedPreferenceChangeListener(this);draw()}
    override fun onPause(){prefs.unregisterOnSharedPreferenceChangeListener(this);super.onPause()}
    override fun onSharedPreferenceChanged(p:SharedPreferences?,k:String?){draw()}
    private fun draw(){if(!::update.isInitialized)return
        val cat=Store.current(this);val active=Tunnel.running
        status.text=if(active)prefs.getString("state","Подключение…") else prefs.getString("error","Выключено")
        start.text=if(active)"Отключить DNS" else "Включить DNS"
        mode.text="Провайдер · "+(cat.providers.find{it.id==prefs.getString("mode","auto")}?.name?:"Автоматически")
        host.text="Сервисы · "+(prefs.getStringSet("hosts",setOf("chatgpt.com","claude.ai"))?:emptySet()).mapNotNull{Probe.sites[it]}.joinToString()
        report.text=prefs.getString("scores","При включении в авторежиме проверим доступность выбранных сайтов.")
        val version=packageManager.getPackageInfo(packageName,0).versionName?:"1.0.0";val tag=prefs.getString("latest","")!!
        summary.text="Приложение "+version+" · каталог №"+cat.rev+"\nИсточник HB AI "+cat.source+" · провайдеров: "+cat.providers.size+
            prefs.getString("catalogChange","")!!.let{if(it.isEmpty())"" else "\n"+it}
        check.isEnabled=!checking;check.text=if(checking)"Проверяю…" else "Проверить GitHub"
        update.isEnabled=!downloading;update.text=if(downloading)"Скачиваю и проверяю APK…" else "Установить "+tag;update.visibility=if(Store.newer(tag,version))View.VISIBLE else View.GONE
    }
}
class QuickTile:TileService(){
    override fun onStartListening(){super.onStartListening();qsTile?.let{it.state=if(Tunnel.running)Tile.STATE_ACTIVE else Tile.STATE_INACTIVE;it.updateTile()}}
    override fun onClick(){super.onClick()
        if(Tunnel.running){startService(Intent(this,Tunnel::class.java).setAction(Tunnel.STOP));return}
        if(VpnService.prepare(this)==null){Tunnel.toggleOn(this);return}
        val i=Intent(this,Main::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("enable",true)
        if(android.os.Build.VERSION.SDK_INT>=34)startActivityAndCollapse(PendingIntent.getActivity(this,53,i,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        else @Suppress("DEPRECATION") startActivityAndCollapse(i)
    }
}
