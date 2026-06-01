package com.waveme.mesh

import android.R
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

class OnboardingAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = 6

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> OnboardingStepFragment.newInstance(
                "Chat Offline, Anywhere.",
                "Connect and chat with people around you even without an internet connection or cell service.",
                R.drawable.ic_menu_share
            )
            1 -> OnboardingStepFragment.newInstance(
                "Create a Mesh Network.",
                "Wave links phones together directly, creating a private, peer-to-peer network on the fly.",
                R.drawable.ic_menu_mylocation
            )
            2 -> OnboardingStepFragment.newInstance(
                "Relay Messages Through Peers.",
                "Your network grows automatically. Messages can hop between users to reach people beyond your direct range.",
                R.drawable.ic_menu_directions
            )
            3 -> OnboardingStepFragment.newInstance(
                "Secure & Serverless.",
                "With no central servers and end-to-end encryption, your conversations are always private and for your eyes only.",
                R.drawable.ic_lock_idle_lock
            )
            4 -> OnboardingStepFragment.newInstance(
                "Community Standards",
                "To keep Wave a safe space, users must follow community standards. Do not share illegal, harmful, or abusive content. You can block any user at any time.",
                R.drawable.ic_menu_info_details
            )
            5 -> OnboardingStepFragment.newInstance(
                "Connectivity Permissions",
                "To find and connect with nearby peers, Wave needs Location, Bluetooth, and Wi-Fi permissions. We never track your location; it's only used for peer discovery.",
                R.drawable.ic_menu_set_as
            )
            else -> throw IllegalStateException("Invalid position: $position")
        }
    }
}