package com.alphawallet.app.util.boc;

import java.io.IOException;

import okhttp3.Credentials;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

public class HttpBasicAuth implements Interceptor {
    private String username;
    private String password;

    public HttpBasicAuth() {
    }

    public String getUsername() {
        return this.username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return this.password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public void setCredentials(String username, String password) {
        this.username = username;
        this.password = password;
    }

    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        if (request.header("Authorization") == null) {
            String credentials = Credentials.basic(this.username, this.password);
            request = request.newBuilder().addHeader("Authorization", credentials).build();
        }

        return chain.proceed(request);
    }
}
