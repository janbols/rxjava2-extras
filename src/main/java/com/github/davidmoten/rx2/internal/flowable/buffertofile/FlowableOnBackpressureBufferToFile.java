package com.github.davidmoten.rx2.internal.flowable.buffertofile;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.github.davidmoten.guavamini.Preconditions;
import com.github.davidmoten.rx2.buffertofile.Options;
import com.github.davidmoten.rx2.buffertofile.Serializer;

import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.Observer;
import io.reactivex.Scheduler.Worker;
import io.reactivex.disposables.Disposable;
import io.reactivex.exceptions.Exceptions;
import io.reactivex.internal.functions.ObjectHelper;
import io.reactivex.internal.subscriptions.SubscriptionHelper;
import io.reactivex.internal.util.BackpressureHelper;
import io.reactivex.plugins.RxJavaPlugins;

public final class FlowableOnBackpressureBufferToFile<T> extends Flowable<T> {

    private final Flowable<T> source;
    private final Observable<T> source2;
    private final Options options;
    private final Serializer<T> serializer;

    public FlowableOnBackpressureBufferToFile(Flowable<T> source, Observable<T> source2,
            Options options, Serializer<T> serializer) {
        Preconditions.checkArgument(
                source != null && source2 == null || source == null && source2 != null);
        this.source = source;
        this.source2 = source2;
        this.options = options;
        this.serializer = serializer;
    }

    @Override
    protected void subscribeActual(Subscriber<? super T> child) {
        PagedQueue queue = new PagedQueue(options.fileFactory(), options.pageSizeBytes());
        Worker worker = options.scheduler().createWorker();
        if (source != null) {
            source.subscribe(
                    new BufferToFileSubscriberFlowable<T>(child, queue, serializer, worker));
        } else {
            source2.subscribe(
                    new BufferToFileSubscriberObservable<T>(child, queue, serializer, worker));
        }
    }

    @SuppressWarnings("serial")
    private static final class BufferToFileSubscriberFlowable<T> extends BufferToFileSubscriber<T>
            implements Subscriber<T>, Subscription {

        private Subscription parent;

        BufferToFileSubscriberFlowable(Subscriber<? super T> child, PagedQueue queue,
                Serializer<T> serializer, Worker worker) {
            super(child, queue, serializer, worker);
        }

        @Override
        public void onSubscribe(Subscription parent) {
            if (SubscriptionHelper.validate(this.parent, parent)) {
                this.parent = parent;
                child.onSubscribe(this);
            }
        }

        @Override
        public void request(long n) {
            if (SubscriptionHelper.validate(n)) {
                BackpressureHelper.add(requested, n);
                parent.request(n);
                scheduleDrain();
            }
        }

        @Override
        public void cancel() {
            cancelled = true;
            parent.cancel();
            // ensure queue is closed from the worker thread
            // to simplify concurrency controls in PagedQueue
            scheduleDrain();
        }

        @Override
        public void onNext(T t) {
            super.onNext(t);
        }

        @Override
        public void onError(Throwable e) {
            super.onError(e);
        }

        @Override
        public void onComplete() {
            super.onComplete();
        }

        @Override
        public void cancelUpstream() {
            parent.cancel();
        }
    }

    @SuppressWarnings("serial")
    private static final class BufferToFileSubscriberObservable<T> extends BufferToFileSubscriber<T>
            implements Observer<T>, Subscription {

        private Disposable parent;

        BufferToFileSubscriberObservable(Subscriber<? super T> child, PagedQueue queue,
                Serializer<T> serializer, Worker worker) {
            super(child, queue, serializer, worker);
        }

        @Override
        public void onSubscribe(Disposable d) {
            this.parent = d;
            child.onSubscribe(this);
        }

        @Override
        public void onNext(T t) {
            super.onNext(t);
        }

        @Override
        public void onError(Throwable e) {
            super.onError(e);
        }

        @Override
        public void onComplete() {
            super.onComplete();
        }

        @Override
        public void cancelUpstream() {
            parent.dispose();
        }

        @Override
        public void request(long n) {
            if (SubscriptionHelper.validate(n)) {
                BackpressureHelper.add(requested, n);
                scheduleDrain();
            }
        }

        @Override
        public void cancel() {
            cancelled = true;
            parent.dispose();
            // ensure queue is closed from the worker thread
            // to simplify concurrency controls in PagedQueue
            scheduleDrain();
        }
    }
    
    private interface HasUpstream {
        void cancelUpstream();
    }

    @SuppressWarnings({ "serial" })
    private static abstract class BufferToFileSubscriber<T> extends AtomicInteger implements Runnable, HasUpstream {

        protected final Subscriber<? super T> child;
        private final PagedQueue queue;
        private final Serializer<T> serializer;
        private final Worker worker;
        protected final AtomicLong requested = new AtomicLong();

        protected volatile boolean cancelled;
        private volatile boolean done;

        // Is set just before the volatile `done` is set and read just after
        // `done` is read. Thus doesn't need to be volatile.
        private Throwable error;

        BufferToFileSubscriber(Subscriber<? super T> child, PagedQueue queue,
                Serializer<T> serializer, Worker worker) {
            this.child = child;
            this.queue = queue;
            this.serializer = serializer;
            this.worker = worker;
        }

        public void onNext(T t) {
            try {
                queue.offer(serializer.serialize(t));
            } catch (Throwable e) {
                Exceptions.throwIfFatal(e);
                onError(e);
                return;
            }
            scheduleDrain();
        }

        public void onError(Throwable e) {
            // must assign error before assign done = true to avoid race
            // condition in drain() and also so appropriate memory barrier in
            // place given error is non-volatile
            error = e;
            done = true;
            scheduleDrain();
        }

        public void onComplete() {
            done = true;
            scheduleDrain();
        }

        protected void scheduleDrain() {
            // only schedule a drain if current drain has finished
            // otherwise the drain requested counter (`this`) will be
            // incremented and the drain loop will ensure that another drain
            // cycle occurs if required
            if (getAndIncrement() == 0) {
                worker.schedule(this);
            }
        }

        @Override
        public void run() {
            drain();
        }

        private void drain() {
            int missed = 1;
            // check cancel outside of request drain loop because the drain
            // method is also used to serialize read with cancellation (closing
            // the queue) and we still want it to happen if there are no
            // requests
            if (cancelled) {
                queue.close();
                worker.dispose();
                return;
            }
            while (true) {
                long r = requested.get();
                long e = 0; // emitted
                try {
                    while (e != r) {
                        if (cancelled) {
                            queue.close();
                            worker.dispose();
                            return;
                        }
                        // for visibility purposes must read error AFTER reading
                        // done (done is volatile and error is non-volatile)
                        boolean isDone = done;
                        if (error != null) {
                            cancelNow();
                            child.onError(error);
                            return;
                        }
                        byte[] bytes = queue.poll();
                        if (bytes != null) {
                            // assumed to be fast so we don't check cancelled
                            // after this call
                            T t = ObjectHelper.requireNonNull( //
                                    serializer.deserialize(bytes),
                                    "Serializer.deserialize should not return null (because RxJava 2 does not support streams with null items");
                            child.onNext(t);
                            e++;
                        } else if (isDone) {
                            cancelNow();
                            child.onComplete();
                            return;
                        } else {
                            break;
                        }
                    }
                } catch (Throwable err) {
                    Exceptions.throwIfFatal(err);
                    boolean c = cancelled;
                    cancelNow();
                    if (!c) {
                        child.onError(err);
                    } else {
                        RxJavaPlugins.onError(err);
                    }
                    return;
                }
                if (e > 0) {
                    BackpressureHelper.produced(requested, e);
                }
                missed = addAndGet(-missed);
                if (missed == 0) {
                    return;
                }
            }
        }

        private void cancelNow() {
            cancelled = true;
            cancelUpstream();
            queue.close();
            worker.dispose();
        }

    }

}
