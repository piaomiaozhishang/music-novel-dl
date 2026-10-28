package com.wang.sonovel.music

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.security.*
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

internal object LxUtilities {
    private fun bytes(value: JsonElement): ByteArray = value.asJsonArray.map { it.asInt.toByte() }.toByteArray()
    fun call(json: String): Any {
        val a=JsonParser.parseString(json).asJsonArray
        fun b(i:Int)=bytes(a[i])
        fun s(i:Int)=a[i].asString
        val result: Any = when(s(0)) {
            "encode" -> when(s(2).lowercase()) {
                "base64" -> Base64.decode(s(1),Base64.DEFAULT)
                "hex" -> s(1).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                "binary","latin1","ascii" -> s(1).map { it.code.toByte() }.toByteArray()
                "utf16le","ucs2","ucs-2" -> s(1).toByteArray(Charsets.UTF_16LE)
                else -> s(1).toByteArray(Charsets.UTF_8)
            }
            "decode" -> when(s(2).lowercase()) {
                "base64" -> Base64.encodeToString(b(1),Base64.NO_WRAP)
                "hex" -> b(1).joinToString("") { "%02x".format(it) }
                "binary","latin1" -> b(1).map { (it.toInt() and 255).toChar() }.joinToString("")
                "ascii" -> b(1).map { (it.toInt() and 127).toChar() }.joinToString("")
                "utf16le","ucs2","ucs-2" -> String(b(1),Charsets.UTF_16LE)
                else -> String(b(1),Charsets.UTF_8)
            }
            "md5" -> MessageDigest.getInstance("MD5").digest(b(1)).joinToString("") { "%02x".format(it) }
            "random" -> ByteArray(a[1].asInt.also { require(it in 0..4096) }).also { SecureRandom().nextBytes(it) }
            "aes" -> {
                val mode=s(2).lowercase()
                require(Regex("aes-(128|192|256)-(cbc|ecb)").matches(mode)) { "不支持的 AES 模式：$mode" }
                val key=b(3); require(key.size*8==mode.split('-')[1].toInt()) { "AES 密钥长度不匹配" }
                Cipher.getInstance(if(mode.endsWith("ecb")) "AES/ECB/PKCS5Padding" else "AES/CBC/PKCS5Padding").run {
                    if(mode.endsWith("ecb")) init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"))
                    else init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),IvParameterSpec(b(4)))
                    doFinal(b(1))
                }
            }
            "rsa" -> {
                val pem=s(2)
                var der=Base64.decode(pem.replace(Regex("-----[^-]+-----|\\s"),""),Base64.DEFAULT)
                if(pem.contains("BEGIN RSA PUBLIC KEY")) {
                    fun length(n:Int)=if(n<128) byteArrayOf(n.toByte()) else if(n<256) byteArrayOf(0x81.toByte(),n.toByte()) else byteArrayOf(0x82.toByte(),(n shr 8).toByte(),n.toByte())
                    val algorithm=byteArrayOf(0x30,0x0d,0x06,0x09,0x2a,0x86.toByte(),0x48,0x86.toByte(),0xf7.toByte(),0x0d,0x01,0x01,0x01,0x05,0x00)
                    val body=algorithm+byteArrayOf(0x03)+length(der.size+1)+byteArrayOf(0)+der
                    der=byteArrayOf(0x30)+length(body.size)+body
                }
                val key=KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der)) as RSAPublicKey
                val size=(key.modulus.bitLength()+7)/8
                val plain=b(1); require(plain.size<=size)
                Cipher.getInstance("RSA/ECB/NoPadding").run { init(Cipher.ENCRYPT_MODE,key); doFinal(ByteArray(size-plain.size)+plain) }
            }
            "inflate" -> InflaterInputStream(b(1).inputStream()).use { it.readBytesLimited(4*1024*1024) }
            "deflate" -> java.io.ByteArrayOutputStream().also { out -> DeflaterOutputStream(out).use { it.write(b(1)) } }.toByteArray()
            else -> error("未知音源工具")
        }
        return if(result is ByteArray) result.map { it.toInt() and 255 } else result
    }
    val javascript = """
        function __util(){var r=JSON.parse(__nativeUtil(JSON.stringify(Array.from(arguments))));if(r.error)throw Error(r.error);return r.value}
        class Buffer extends Uint8Array {
          static from(v,encoding='utf8',length){
            if(typeof v==='string')return new Buffer(__util('encode',v,encoding));
            if(v instanceof ArrayBuffer)return new Buffer(v,typeof encoding==='number'?encoding:0,length);
            if(v&&v.type==='Buffer')v=v.data;
            return new Buffer(v);
          }
          static alloc(n,fill=0,enc){if(n>4194304)throw Error('buffer too large');var b=new Buffer(n);if(typeof fill==='string'){var a=Buffer.from(fill,enc);if(a.length)for(var i=0;i<n;i++)b[i]=a[i%a.length]}else b.fill(fill);return b}
          static allocUnsafe(n){return Buffer.alloc(n)}
          static isBuffer(b){return b instanceof Buffer}
          static byteLength(s,enc){return Buffer.from(s,enc).length}
          static concat(arr,length){var n=length===undefined?arr.reduce((s,a)=>s+a.length,0):length;var b=Buffer.alloc(n),p=0;for(var a of arr){var c=a.subarray(0,n-p);b.set(c,p);p+=c.length;if(p>=n)break}return b}
          toString(enc='utf8',start=0,end=this.length){return __util('decode',Array.from(this.subarray(start,end)),enc)}
          toJSON(){return {type:'Buffer',data:Array.from(this)}}
          equals(b){return this.length===b.length&&this.every((v,i)=>v===b[i])}
          readUInt32BE(o=0){return new DataView(this.buffer,this.byteOffset,this.byteLength).getUint32(o)}
          readUInt32LE(o=0){return new DataView(this.buffer,this.byteOffset,this.byteLength).getUint32(o,true)}
          writeUInt32BE(v,o=0){new DataView(this.buffer,this.byteOffset,this.byteLength).setUint32(o,v);return o+4}
          writeUInt32LE(v,o=0){new DataView(this.buffer,this.byteOffset,this.byteLength).setUint32(o,v,true);return o+4}
        }
        function __bytes(b){return Array.from(Buffer.from(b))}
        function btoa(s){return Buffer.from(s,'binary').toString('base64')}
        function atob(s){return Buffer.from(s,'base64').toString('binary')}
        var __utils={
          buffer:{from:Buffer.from,bufToString(b,enc){return Buffer.from(b,'binary').toString(enc)}},
          crypto:{
            md5(s){return __util('md5',__bytes(s))},
            randomBytes(n){return Buffer.from(__util('random',n))},
            aesEncrypt(b,mode,key,iv){return Buffer.from(__util('aes',__bytes(b),mode,__bytes(key),__bytes(iv||[])))},
            rsaEncrypt(b,key){return Buffer.from(__util('rsa',__bytes(b),key))}
          },
          zlib:{
            inflate(b){return Promise.resolve().then(()=>Buffer.from(__util('inflate',__bytes(b))))},
            deflate(b){return Promise.resolve().then(()=>Buffer.from(__util('deflate',__bytes(b))))}
          }
        };
    """.trimIndent()
}
