package com.banupham.appviewcamera.viewer.security

interface CredentialCipher {
    fun encrypt(plainText: String): String
    fun decrypt(cipherText: String): String
}
