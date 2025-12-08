package it.janczewski.examples;

import it.janczewski.examples.utils.Reciver;
import it.janczewski.examples.utils.Sender;

public class App {

    public static void main(String[] args) throws Exception {
        // Run sender and receiver in parallel or sequence depending on the test
        // The original example runs them in sequence with a sleep
        
        // We might want to run them continuously for a bit to see the proxy in action
        // But let's stick to the original logic first, maybe loop it
        
        for (int i = 0; i < 10; i++) {
            Sender sender = new Sender();
            sender.createTask();
        }
        
        Thread.sleep(1000);
        
        for (int i = 0; i < 10; i++) {
            Reciver reciver = new Reciver();
            reciver.createRecieveTask();
        }
        
        // Keep alive for a bit to ensure tasks finish
        Thread.sleep(5000);
    }
}
