package com.example.fitbitmore.data

import android.content.Context
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
import com.google.api.client.json.gson.GsonFactory
import java.io.InputStreamReader

object CredentialsHelper {
    fun loadGoogleCredentials(context: Context): GoogleClientSecrets {
        val inputStream = context.assets.open("credentials.json")
        return GoogleClientSecrets.load(
            GsonFactory.getDefaultInstance(),
            InputStreamReader(inputStream)
        )
    }
}