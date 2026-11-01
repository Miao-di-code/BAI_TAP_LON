package com.btl.auction.client.view;

public interface Screen {
    void setApp(AppController app);

    default void leave() {
    }
}
