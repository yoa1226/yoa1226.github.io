import java.util.ArrayList;

public class SyncTest {
    private Object lock = new Object();
    private Object lock1 = new Object();

    public static void main(String[] args) {
        new Thread(()->{
                new SyncTest().testCountMonitorInMethod();
            }).start();
        smallObj();
    }

    public void testCountMonitorInMethod() {
       synchronized (lock) {
           synchronized (lock1) {
             sleep();
           }
       }
    }
    
    public static void smallObj() {
        ArrayList<byte[]> list = new ArrayList<byte[]>();
        for (int i = 0; i < 100000; i++) {
            list.add(new byte[1024*1024/2]);
        }
    }

    public void sleep(){
        try {
                Thread.sleep(1000_1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
        }
    }
}
