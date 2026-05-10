package com.sulkan.core.render;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public final class RenderWorker {
	private static final int MAX_THREADS = 4;
	private static final int THREAD_COUNT = Math.max(1, Math.min(MAX_THREADS, Runtime.getRuntime().availableProcessors() - 1));
	private static final AtomicInteger THREAD_ID = new AtomicInteger();
	private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(
			THREAD_COUNT,
			runnable -> {
				Thread thread = new Thread(runnable, "Sulkan Render Worker " + THREAD_ID.incrementAndGet());
				thread.setDaemon(true);
				return thread;
			});

	private RenderWorker() {
	}

	public static int threadCount() {
		return THREAD_COUNT;
	}

	public static <T> CompletableFuture<T> submit(Supplier<T> task) {
		return CompletableFuture.supplyAsync(task, EXECUTOR);
	}

	public static void shutdown() {
		EXECUTOR.shutdownNow();
	}
}
