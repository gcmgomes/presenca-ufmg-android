package com.example.presensor.controllers.providers

import android.view.LayoutInflater
import com.example.presensor.MainActivity

class AndroidDashboardInteractionProvider(
    activity: MainActivity
) : BaseAndroidInteractionProvider(activity), DashboardInteractionProvider {
    override fun getLayoutInflater(): LayoutInflater = activity.layoutInflater
}
