package com.fileforge.optimizer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.card.MaterialCardView

class MainActivity : AppCompatActivity() {
    private lateinit var toolbar: MaterialToolbar
    private lateinit var contentContainer: FrameLayout
    private lateinit var bottomNavigation: BottomNavigationView
    private lateinit var optimizeController: OptimizeScreenController
    private var serviceBinder: OptimizationBinder? = null
    private var serviceBound = false

    private val runStateListener: (RunState) -> Unit = { state ->
        runOnUiThread { optimizeController.render(state) }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? OptimizationBinder ?: return
            serviceBinder = binder
            binder.addListener(runStateListener)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBinder?.removeListener(runStateListener)
            serviceBinder = null
        }

        override fun onNullBinding(name: ComponentName?) {
            serviceBinder = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePreferences.applyActivityThemeBeforeOnCreate(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        optimizeController = OptimizeScreenController(
            activity = this,
            startRun = { request -> OptimizationService.start(this, request) },
            cancelRun = ::cancelActiveRun
        )
        buildMaterialHost()
    }

    override fun onStart() {
        super.onStart()
        serviceBound = bindService(
            Intent(this, OptimizationService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onStop() {
        serviceBinder?.removeListener(runStateListener)
        serviceBinder = null
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onStop()
    }

    private fun buildMaterialHost() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        toolbar = MaterialToolbar(this).apply {
            id = R.id.toolbar
            setTitle(R.string.navigation_optimize)
            contentDescription = getString(R.string.toolbar_description)
        }
        root.addView(
            toolbar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        contentContainer = FrameLayout(this).apply { id = R.id.content_container }
        root.addView(
            contentContainer,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        bottomNavigation = BottomNavigationView(this).apply {
            id = R.id.bottom_navigation
            contentDescription = getString(R.string.bottom_navigation_description)
            menu.add(Menu.NONE, R.id.navigation_optimize, 0, R.string.navigation_optimize)
                .setIcon(android.R.drawable.ic_menu_manage)
            menu.add(Menu.NONE, R.id.navigation_restore, 1, R.string.navigation_restore)
                .setIcon(android.R.drawable.ic_menu_revert)
            menu.add(Menu.NONE, R.id.navigation_about, 2, R.string.navigation_about)
                .setIcon(android.R.drawable.ic_menu_info_details)
            setOnItemSelectedListener { item ->
                showDestination(item.itemId)
                true
            }
        }
        root.addView(
            bottomNavigation,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        setContentView(root)
        applySystemBarInsets(root)
        bottomNavigation.selectedItemId = R.id.navigation_optimize
    }

    private fun showDestination(itemId: Int) {
        contentContainer.removeAllViews()
        when (itemId) {
            R.id.navigation_restore -> {
                toolbar.setTitle(R.string.navigation_restore)
                contentContainer.addView(
                    placeholder(R.string.restore_placeholder_title, R.string.restore_placeholder_body)
                )
            }
            R.id.navigation_about -> {
                toolbar.setTitle(R.string.navigation_about)
                contentContainer.addView(
                    placeholder(R.string.about_placeholder_title, R.string.about_placeholder_body)
                )
            }
            else -> {
                toolbar.setTitle(R.string.navigation_optimize)
                (optimizeController.view.parent as? ViewGroup)?.removeView(optimizeController.view)
                contentContainer.addView(optimizeController.view)
            }
        }
    }

    private fun placeholder(title: Int, body: Int): View = FrameLayout(this).apply {
        val margin = dp(16)
        addView(
            MaterialCardView(this@MainActivity).apply {
                val content = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(20), dp(20), dp(20), dp(20))
                    addView(TextView(this@MainActivity).apply {
                        id = R.id.placeholder_title
                        setText(title)
                        textSize = 22f
                    })
                    addView(TextView(this@MainActivity).apply {
                        setText(body)
                        setPadding(0, dp(8), 0, 0)
                    })
                }
                addView(content)
            },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(margin, margin, margin, margin) }
        )
    }

    private fun cancelActiveRun() {
        startService(
            Intent(this, OptimizationService::class.java)
                .setAction(OptimizationService.ACTION_CANCEL)
        )
    }

    private fun applySystemBarInsets(root: View) {
        val toolbarLeft = toolbar.paddingLeft
        val toolbarTop = toolbar.paddingTop
        val toolbarRight = toolbar.paddingRight
        val toolbarBottom = toolbar.paddingBottom
        val navigationLeft = bottomNavigation.paddingLeft
        val navigationTop = bottomNavigation.paddingTop
        val navigationRight = bottomNavigation.paddingRight
        val navigationBottom = bottomNavigation.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(insets.left, 0, insets.right, 0)
            toolbar.setPadding(
                toolbarLeft,
                toolbarTop + insets.top,
                toolbarRight,
                toolbarBottom
            )
            bottomNavigation.setPadding(
                navigationLeft,
                navigationTop,
                navigationRight,
                navigationBottom + insets.bottom
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
