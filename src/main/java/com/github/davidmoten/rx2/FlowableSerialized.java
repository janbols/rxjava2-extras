package com.github.davidmoten.rx2;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.reactivex.Emitter;
import io.reactivex.Flowable;
import io.reactivex.functions.Consumer;
import io.reactivex.functions.Function;

import java.io.*;
import java.util.concurrent.Callable;

/**
 * Utility class for writing {@link Flowable} streams to {@link ObjectOutputStream}s and
 * reading {@link Flowable} streams of indeterminate size from {@link ObjectInputStream}s.
 */
public final class FlowableSerialized {

    private static final int DEFAULT_BUFFER_SIZE = 8192;

    /**
     * Returns the deserialized objects from the given {@link InputStream} as an
     * {@link Flowable} stream.
     * 
     * @param ois
     *            the {@link ObjectInputStream}
     * @param <T>
     *            the generic type of the returned stream
     * @return the stream of deserialized objects from the {@link InputStream}
     *         as an {@link Flowable}.
     */
    public static <T extends Serializable> Flowable<T> read(final ObjectInputStream ois) {
        return Flowable.generate(new Consumer<Emitter<T>>(){
            @Override
            public void accept(Emitter<T> emitter) throws Exception {
                try {
                    @SuppressWarnings("unchecked")
                    T t = (T) ois.readObject();
                    emitter.onNext(t);
                } catch (EOFException e) {
                    emitter.onComplete();
                } catch (ClassNotFoundException e) {
                    emitter.onError(e);
                } catch (IOException e) {
                    emitter.onError(e);
                }
            }
        });
    }

    /**
     * Returns the deserialized objects from the given {@link File} as an
     * {@link Flowable} stream. Uses buffer of size <code>bufferSize</code>
     * buffer reads from the File.
     * 
     * @param file
     *            the input file
     * @param bufferSize
     *            the buffer size for reading bytes from the file.
     * @param <T>
     *            the generic type of the deserialized objects returned in the
     *            stream
     * @return the stream of deserialized objects from the {@link InputStream}
     *         as an {@link Flowable}.
     */
    public static <T extends Serializable> Flowable<T> read(final File file,
            final int bufferSize) {
        Callable<ObjectInputStream> resourceFactory = new Callable<ObjectInputStream>() {
            @Override
            public ObjectInputStream call() throws IOException {
                    return new ObjectInputStream(
                            new BufferedInputStream(new FileInputStream(file), bufferSize));
            }
        };
        Function<ObjectInputStream, Flowable<? extends T>> observableFactory = new Function<ObjectInputStream, Flowable<? extends T>>() {

            @Override
            public Flowable<? extends T> apply(ObjectInputStream is) throws Exception {
                return read(is);
            }

        };
        Consumer<ObjectInputStream> disposeAction = new Consumer<ObjectInputStream>() {

            @Override
            public void accept(ObjectInputStream ois) throws IOException {
                    ois.close();
            }
        };
        return Flowable.using(resourceFactory, observableFactory, disposeAction, true);
    }

    /**
     * Returns the deserialized objects from the given {@link File} as an
     * {@link Flowable} stream. A buffer size of 8192 bytes is used by
     * default.
     * 
     * @param file
     *            the input file containing serialized java objects
     * @param <T>
     *            the generic type of the deserialized objects returned in the
     *            stream
     * @return the stream of deserialized objects from the {@link InputStream}
     *         as an {@link Flowable}.
     */
    public static <T extends Serializable> Flowable<T> read(final File file) {
        return read(file, DEFAULT_BUFFER_SIZE);
    }

    /**
     * Returns a duplicate of the input stream but with the side effect that
     * emissions from the source are written to the {@link ObjectOutputStream}.
     * 
     * @param source
     *            the source of objects to write
     * @param oos
     *            the output stream to write to
     * @param <T>
     *            the generic type of the objects being serialized
     * @return re-emits the input stream
     */
    public static <T extends Serializable> Flowable<T> write(Flowable<T> source,
            final ObjectOutputStream oos) {
        return source.doOnNext(new Consumer<T>() {

            @Override
            public void accept(T t) throws IOException {
                    oos.writeObject(t);
            }
        });
    }

    /**
     * Writes the source stream to the given file in given append mode and using
     * the given buffer size.
     * 
     * @param source
     *            flowable stream to write
     * @param file
     *            file to write to
     * @param append
     *            if true writes are appended to file otherwise overwrite the
     *            file
     * @param bufferSize
     *            the buffer size in bytes to use.
     * @param <T>
     *            the generic type of the input stream
     * @return re-emits the input stream
     */
    public static <T extends Serializable> Flowable<T> write(final Flowable<T> source,
            final File file, final boolean append, final int bufferSize) {
        Callable<ObjectOutputStream> resourceFactory = new Callable<ObjectOutputStream>() {
            @Override
            public ObjectOutputStream call() throws IOException {
                    return new ObjectOutputStream(new BufferedOutputStream(
                            new FileOutputStream(file, append), bufferSize));
            }
        };
        Function<ObjectOutputStream, Flowable<? extends T>> observableFactory = new Function<ObjectOutputStream, Flowable<? extends T>>() {

            @Override
            public Flowable<? extends T> apply(ObjectOutputStream oos) {
                return write(source, oos);
            }
        };
        Consumer<ObjectOutputStream> disposeAction = new Consumer<ObjectOutputStream>() {

            @Override
            public void accept(ObjectOutputStream oos) throws IOException {
                    oos.close();
            }
        };
        return Flowable.using(resourceFactory, observableFactory, disposeAction, true);
    }

    /**
     * Writes the source stream to the given file in given append mode and using
     * the a buffer size of 8192 bytes.
     * 
     * @param source
     *            flowable stream to write
     * @param file
     *            file to write to
     * @param append
     *            if true writes are appended to file otherwise overwrite the
     *            file
     * @param <T>
     *            the generic type of the input stream
     * @return re-emits the input stream
     */
    public static <T extends Serializable> Flowable<T> write(final Flowable<T> source,
            final File file, final boolean append) {
        return write(source, file, append, DEFAULT_BUFFER_SIZE);
    }

    /**
     * Writes the source stream to the given file in given append mode and using
     * the a buffer size of 8192 bytes.
     * 
     * @param source
     *            flowable stream to write
     * @param file
     *            file to write to
     * @param <T>
     *            the generic type of the input stream
     * @return re-emits the input stream
     */
    public static <T extends Serializable> Flowable<T> write(final Flowable<T> source,
            final File file) {
        return write(source, file, false, DEFAULT_BUFFER_SIZE);
    }

    public static KryoBuilder kryo() {
        return kryo(new Kryo());
    }

    public static KryoBuilder kryo(Kryo kryo) {
        return new KryoBuilder(kryo);
    }

    public static class KryoBuilder {

        private static final int DEFAULT_BUFFER_SIZE = 4096;

        private final Kryo kryo;

        private KryoBuilder(Kryo kryo) {
            this.kryo = kryo;
        }

        public <T> Flowable<T> write(final Flowable<T> source, final File file) {
            return write(source, file, false, DEFAULT_BUFFER_SIZE);
        }

        public <T> Flowable<T> write(final Flowable<T> source, final File file,
                boolean append) {
            return write(source, file, append, DEFAULT_BUFFER_SIZE);
        }

        public <T> Flowable<T> write(final Flowable<T> source, final File file,
                final boolean append, final int bufferSize) {
            Callable<Output> resourceFactory = new Callable<Output>() {
                @Override
                public Output call() throws FileNotFoundException {
                        return new Output(new FileOutputStream(file, append), bufferSize);
                }
            };
            Function<Output, Flowable<? extends T>> observableFactory = new Function<Output, Flowable<? extends T>>() {

                @Override
                public Flowable<? extends T> apply(final Output output) {
                    return source.doOnNext(new Consumer<T>() {
                        @Override
                        public void accept(T t) {
                            kryo.writeObject(output, t);
                        }
                    });
                }
            };
            Consumer<Output> disposeAction = new Consumer<Output>() {

                @Override
                public void accept(Output output) {
                    output.close();
                }
            };
            return Flowable.using(resourceFactory, observableFactory, disposeAction, true);
        }

        public <T> Flowable<T> read(Class<T> cls, final File file) {
            return read(cls, file, DEFAULT_BUFFER_SIZE);
        }

        public <T> Flowable<T> read(final Class<T> cls, final File file, final int bufferSize) {
            Callable<Input> resourceFactory = new Callable<Input>() {
                @Override
                public Input call() throws FileNotFoundException {
                        return new Input(new FileInputStream(file), bufferSize);
                }
            };
            Function<Input, Flowable<? extends T>> observableFactory = new Function<Input, Flowable<? extends T>>() {

                @Override
                public Flowable<? extends T> apply(final Input input) {
                    return read(cls, input);
                }
            };
            Consumer<Input> disposeAction = new Consumer<Input>() {

                @Override
                public void accept(Input input) {
                    input.close();
                }
            };
            return Flowable.using(resourceFactory, observableFactory, disposeAction, true);
        }

        public <T> Flowable<T> read(final Class<T> cls, final Input input) {

            return Flowable.generate(new Consumer<Emitter<T>>() {

                @Override
                public void accept(Emitter<T> emitter) throws Exception {
                    if (input.eof()) {
                        emitter.onComplete();
                    } else {
                        T t = kryo.readObject(input, cls);
                        emitter.onNext(t);
                    }
                }

            });
        }
    }

}
