package com.banupham.appviewcamera.gateway.security

interface CredentialCipher {
    fun encrypt(plainText: String): String
    fun decrypt(cipherText: String): String
}
