package jol;

import org.openjdk.jol.info.ClassLayout;
import org.openjdk.jol.vm.VM;

import static java.lang.System.out;

/**
 * @author Aleksey Shipilev
 */
public class JOLSample_01_Age {

    public static void main(String[] args) {
        A a = new A();
        for (int i = 0; i < 500; i++) {
            var bytes = new byte[1024 * 1024];
        }
        out.println(ClassLayout.parseInstance(a).toPrintable());
        long markword = VM.current().getLong(a, 0);
        printAgeBit(markword);
    }

    public static class A {
        boolean f;
    }

    public static void printMarkWordBin(long markword) {
        out.printf("markword bit: %s\n", toBinaryWithSpaces(markword));
    }

    public static void printMarkBit(long markword) {
        out.printf("mark bit: %s\n", toBinaryWithSpaces(markword & 0b11));
    }

    public static void printbiasBit(long markword) {
        out.printf("bias bit: %s\n", toBinaryWithSpaces(markword >> 2 & 0b1));
    }

    public static void printAgeBit(long markword) {
        out.printf("age bit: %s\n", toBinaryWithSpaces(markword >> 3 & 0b1111));
    }

    public static void printHashcodeBit(long markword) {
        out.printf("hash code bit: %s\n", toBinaryWithSpaces((markword >>> 8)));
    }


    public static String toHexWithSpaces(long number) {
        String hex = Long.toHexString(number);
        // 如果长度是奇数，在前面补零
        if (hex.length() % 2 != 0) {
            hex = "0" + hex;
        }
        // 每两位分隔开
        return hex.replaceAll("(?<=..)(..)", " $1").toUpperCase();
    }

    public static String toBinaryWithSpaces(long number) {
        String binary = Long.toBinaryString(number);
        // 如果长度不是8的倍数，前面补零
        while (binary.length() % 8 != 0) {
            binary = "0" + binary;
        }
        // 每8位分隔开
        return binary.replaceAll("(.{8})", "$1 ");
    }

}