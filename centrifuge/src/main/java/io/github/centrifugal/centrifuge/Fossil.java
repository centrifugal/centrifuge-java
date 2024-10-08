package io.github.centrifugal.centrifuge;

import java.io.ByteArrayOutputStream;


class Fossil {

    private static final int[] zValue = new int[] {
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 0, 1, 2, 3, 4, 5, 6, 7,
        8, 9, -1, -1, -1, -1, -1, -1, -1, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
        20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, -1, -1, -1,
        -1, 36, -1, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50,
        51, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, 62, -1, -1, -1, 63, -1,
    };

    // Reader class
    static class Reader {
        private byte[] a;
        private int pos;

        public Reader(byte[] array) {
            this.a = array;
            this.pos = 0;
        }

        public boolean haveBytes() {
            return this.pos < this.a.length;
        }

        public int getByte() {
            if (this.pos >= this.a.length) {
                throw new IndexOutOfBoundsException("out of bounds");
            }
            int b = this.a[this.pos++] & 0xFF;
            return b;
        }

        public char getChar() {
            return (char) getByte();
        }

        public int getInt() {
            int v = 0;
            int c;
            while (haveBytes() && (c = zValue[getByte() & 0x7F]) >= 0) {
                v = (v << 6) + c;
            }
            this.pos--;
            return v;
        }
    }

    // Writer class
    static class Writer {
        private ByteArrayOutputStream a = new ByteArrayOutputStream();

        public byte[] toByteArray() {
            return a.toByteArray();
        }

        // Copy from array 'arr' from 'start' to 'end' (exclusive)
        public void putArray(byte[] arr, int start, int end) {
            if (start < 0 || end > arr.length || start > end) {
                throw new IndexOutOfBoundsException("Invalid start or end index");
            }
            a.write(arr, start, end - start);
        }
    }

    // Checksum function
    public static long checksum(byte[] arr) {
        int sum0 = 0, sum1 = 0, sum2 = 0, sum3 = 0;
        int z = 0;
        int N = arr.length;
        while (N >= 16) {
            sum0 += (arr[z + 0] & 0xFF);
            sum1 += (arr[z + 1] & 0xFF);
            sum2 += (arr[z + 2] & 0xFF);
            sum3 += (arr[z + 3] & 0xFF);

            sum0 += (arr[z + 4] & 0xFF);
            sum1 += (arr[z + 5] & 0xFF);
            sum2 += (arr[z + 6] & 0xFF);
            sum3 += (arr[z + 7] & 0xFF);

            sum0 += (arr[z + 8] & 0xFF);
            sum1 += (arr[z + 9] & 0xFF);
            sum2 += (arr[z + 10] & 0xFF);
            sum3 += (arr[z + 11] & 0xFF);

            sum0 += (arr[z + 12] & 0xFF);
            sum1 += (arr[z + 13] & 0xFF);
            sum2 += (arr[z + 14] & 0xFF);
            sum3 += (arr[z + 15] & 0xFF);

            z += 16;
            N -= 16;
        }
        while (N >= 4) {
            sum0 += (arr[z + 0] & 0xFF);
            sum1 += (arr[z + 1] & 0xFF);
            sum2 += (arr[z + 2] & 0xFF);
            sum3 += (arr[z + 3] & 0xFF);
            z += 4;
            N -= 4;
        }
        sum3 += (sum2 << 8) + (sum1 << 16) + (sum0 << 24);
        switch (N) {
            case 3:
                sum3 += (arr[z + 2] & 0xFF) << 8;
            case 2:
                sum3 += (arr[z + 1] & 0xFF) << 16;
            case 1:
                sum3 += (arr[z + 0] & 0xFF) << 24;
                break;
            default:
                break;
        }
        return sum3 & 0xFFFFFFFFL;
    }

    /**
     * Apply a delta byte array to a source byte array, returning the target byte array.
     */
    public static byte[] applyDelta(byte[] source, byte[] delta) throws Exception {
        int total = 0;
        Reader zDelta = new Reader(delta);
        int lenSrc = source.length;
        int lenDelta = delta.length;

        int limit = zDelta.getInt();
        char c = zDelta.getChar();
        if (c != '\n') {
            throw new Exception("size integer not terminated by '\\n'");
        }
        Writer zOut = new Writer();
        while (zDelta.haveBytes()) {
            int cnt = zDelta.getInt();
            int ofst;

            c = zDelta.getChar();
            switch (c) {
                case '@':
                    ofst = zDelta.getInt();
                    if (zDelta.haveBytes() && zDelta.getChar() != ',') {
                        throw new Exception("copy command not terminated by ','");
                    }
                    total += cnt;
                    if (total > limit) {
                        throw new Exception("copy exceeds output file size");
                    }
                    if (ofst + cnt > lenSrc) {
                        throw new Exception("copy extends past end of input");
                    }
                    zOut.putArray(source, ofst, ofst + cnt);
                    break;

                case ':':
                    total += cnt;
                    if (total > limit) {
                        throw new Exception("insert command gives an output larger than predicted");
                    }
                    if (cnt > lenDelta - zDelta.pos) {
                        throw new Exception("insert count exceeds size of delta");
                    }
                    zOut.putArray(zDelta.a, zDelta.pos, zDelta.pos + cnt);
                    zDelta.pos += cnt;
                    break;

                case ';':
                    byte[] out = zOut.toByteArray();
                    long checksumValue = checksum(out);
                    if (cnt != (int) checksumValue) {
                        throw new Exception("bad checksum");
                    }
                    if (total != limit) {
                        throw new Exception("generated size does not match predicted size");
                    }
                    return out;

                default:
                    System.out.println(c);
                    throw new Exception("unknown delta operator");
            }
        }
        throw new Exception("unterminated delta");
    }

}
