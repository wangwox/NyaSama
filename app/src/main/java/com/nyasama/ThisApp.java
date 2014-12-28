package com.nyasama;

import android.app.AlarmManager;
import android.app.Application;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.preference.PreferenceManager;
import android.webkit.WebView;

import com.android.volley.Cache;
import com.android.volley.Network;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.BasicNetwork;
import com.android.volley.toolbox.DiskBasedCache;
import com.android.volley.toolbox.HurlStack;
import com.android.volley.toolbox.ImageLoader;
import com.nyasama.activity.SplashActivity;
import com.nyasama.util.BitmapLruCache;
import com.nyasama.util.PersistenceCookieStore;

import java.io.File;
import java.io.IOException;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Created by oxyflour on 2014/11/15.
 *
 * application 类里表示了本程序需要的最基本的设定和工具
 * 
 * Volley 是 google 开发的工具包，它提供了更加方便的从 URL 载入图片的方法
 * Volley 的函数文档：
 * http://afzaln.com/volley/
 * 
 * Android developer 文档：
 * http://developer.android.com/training/volley/index.html
 * 
 * 不过这里的调用方法和 Android developer 文档中的方法不大一样，直接参考 javadoc 比较好
 * 
 * Volley 类：
 * requestQueue：是一个 HTML 请求队列，调用 start() 后，就会不断地把队列中的请求发到对应的 URL 去以获得数据
 * imageLoader: 后台线程，用来下载 URL 中的图片并装载到组件上（调用见 MainActivity 中的 Avatar）
 * volleyCache: 缓存 HTML response 的储存器，imageLoader 另有别的储存器
 * 
 * webView 类：
 * webView: 网页浏览器，用于运行javascript代码，用例见util.Discuz
 * cookieStore: 存储 cookie，目前用处不明，用例见util.Discuz
 * 
 */
public class ThisApp extends Application {
    public static Context context;
    public static Cache volleyCache;
    public static RequestQueue requestQueue;
    public static ImageLoader imageLoader;
    public static PersistenceCookieStore cookieStore;
    public static WebView webView;

// 下面的两个函数从sharedpreference 中读取语言设置
    private static Locale getLocale(SharedPreferences preferences) {
        String[] values = context.getResources().getStringArray(R.array.language_preference);
        String language = preferences.getString("language", values[0]);
        if (language.equals(values[0])) return Locale.getDefault();
        if (language.equals(values[1])) return Locale.SIMPLIFIED_CHINESE;
        if (language.equals(values[2])) return Locale.ENGLISH;
        return Locale.getDefault();
    }

    // REF: http://aleung.github.io/blog/2012/10/06/change-locale-in-android-application/
    private static void loadLocaleFromPreference(SharedPreferences preferences) {
        Locale locale = getLocale(preferences);
        Locale.setDefault(locale);
        Configuration config = new Configuration();
        config.locale = locale;
        context.getResources().updateConfiguration(config, context.getResources().getDisplayMetrics());
    }

    @Override
    public void onCreate() {
        super.onCreate();

        context = getApplicationContext();
//获得 32MB 的硬盘存储空间,DiskBasedCache 是Volley 的工具类
        File cacheFile = new File(getCacheDir(), "NyasamaVolleyCache");
        volleyCache = new DiskBasedCache(cacheFile, 1024 * 1024 * 32);

        // REF: http://stackoverflow.com/questions/18786059/change-redirect-policy-of-volley-framework
//创建 HTML 请求队列
        Network network = new BasicNetwork(new HurlStack() {
            @Override
            protected HttpURLConnection createConnection(URL url) throws IOException {
                HttpURLConnection connection = super.createConnection(url);
                if (url.getRef() != null && url.getRef().contains("#noredirect#"))
                    connection.setInstanceFollowRedirects(false);
                return connection;
            }
        });
        requestQueue = new RequestQueue(volleyCache, network);
        requestQueue.start();
        
//创建 imageLoader ，与请求队列绑定，并使用 BitmapLruCache 存储器 （见util）
        ImageLoader.ImageCache imgCache = new BitmapLruCache();
        imageLoader = new ImageLoader(requestQueue, imgCache);
//初始化webView 和 cookieStore 
        cookieStore = new PersistenceCookieStore(context);
        CookieHandler.setDefault(new CookieManager(cookieStore, CookiePolicy.ACCEPT_ALL));

        webView = new WebView(context);
        webView.getSettings().setJavaScriptEnabled(true);
//语言设置，设置的过程在setting activity 中完成
        loadLocaleFromPreference(PreferenceManager.getDefaultSharedPreferences(context));
    }

/*
restart 函数设定为全局函数，更改语言后调用（或出现内部错误调用）
alarmmanager 在0.1秒后激发 pendingintent，pendingintent 是一种自带操作的intent
这里的操作是启动一个activity （getactivities）
启动哪个 activity 则是由 pendingintent 中的另一个intent决定，这里启动的activity 是 splashactivity
Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_CLEAR_TASK
这3个flag 确保启动的 activity 是task中的第一个 activty
exit 的参数并没有特殊含义
*/
    public static void restart() {
        // REF: http://stackoverflow.com/questions/6609414/howto-programatically-restart-android-app
        Intent intent = new Intent(context, SplashActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        Intent[] intents = {intent};
        PendingIntent pendingIntent = PendingIntent.getActivities(ThisApp.context, 0,
                intents,
                PendingIntent.FLAG_ONE_SHOT);
        AlarmManager mgr = (AlarmManager) ThisApp.context.getSystemService(Context.ALARM_SERVICE);
        mgr.set(AlarmManager.RTC, System.currentTimeMillis() + 100, pendingIntent);
        System.exit(2);
    }
}
