package com.local.ktv
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class IgebaAccountTest {
    @Test fun rejectedLoginNeverTrustsIncludedUserInfo() {
        val result = JSONObject().put("Code", -3).put("Reason", "账号或密码错误")
            .put("UserInfo", JSONObject().put("UserId", 123))
        assertThrows(IOException::class.java) { IgebaAccount.parseSession(result) }
        assertNull(IgebaAccount.session)
    }
    @Test fun successRequiresValidUserIdentity() {
        for (value in listOf(JSONObject().put("Code", 1),
            JSONObject().put("Code", 1).put("UserInfo", JSONObject().put("UserId", 0)))) {
            assertThrows(IOException::class.java) { IgebaAccount.parseSession(value) }
        }
    }
    @Test fun validResponseKeepsServerAssignedIdentity() {
        val result = JSONObject().put("Code", 1).put("UserInfo",
            JSONObject().put("UserId", "123").put("NickName", "测试用户"))
        assertEquals(IgebaAccount.Session(123, "测试用户"), IgebaAccount.parseSession(result))
        assertNull(IgebaAccount.session) // Parsing alone must never authenticate.
    }
    @Test fun registrationResponseCannotBeMistakenForLogin() {
        assertThrows(IOException::class.java) {
            IgebaAccount.parseSession(JSONObject().put("Result", 1))
        }
    }
}
