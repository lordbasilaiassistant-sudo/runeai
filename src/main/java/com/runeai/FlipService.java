package com.runeai;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Live GE flip intelligence from the wiki prices API — tax-aware margins,
 * suggested buy/sell prices, throughput bounded by buy limits and volume.
 * Suggestions only; RuneAI never trades for you.
 */
@Slf4j
@Singleton
class FlipService
{
	private static final String API = "https://prices.runescape.wiki/api/v1/osrs";
	private static final String UA = "RuneAI RuneLite plugin (github.com/lordbasilaiassistant-sudo/runeai)";
	private static final long REFRESH_MS = 5 * 60_000;

	private final OkHttpClient http;
	private final Gson gson;

	private final Map<Integer, int[]> limits = new ConcurrentHashMap<>();   // id -> {limit}
	private final Map<Integer, String> names = new ConcurrentHashMap<>();
	private volatile List<Flip> topFlips = List.of();
	private final AtomicLong lastRefresh = new AtomicLong();

	@Value
	static class Flip
	{
		String name;
		int buyAt;
		int sellAt;
		int net;
		double roi;
		long gpHr;
	}

	@Inject
	FlipService(OkHttpClient http, Gson gson)
	{
		this.http = http;
		this.gson = gson;
	}

	List<Flip> getTopFlips()
	{
		return topFlips;
	}

	static int geTax(int sellPrice)
	{
		if (sellPrice < 50)
		{
			return 0;
		}
		return (int) Math.min(sellPrice * 0.02, 5_000_000);
	}

	void maybeRefresh()
	{
		final long now = System.currentTimeMillis();
		if (now - lastRefresh.get() < REFRESH_MS)
		{
			return;
		}
		lastRefresh.set(now);
		if (names.isEmpty())
		{
			get("/mapping", body ->
			{
				for (JsonElement e : gson.fromJson(body, com.google.gson.JsonArray.class))
				{
					final JsonObject o = e.getAsJsonObject();
					final int id = o.get("id").getAsInt();
					names.put(id, o.get("name").getAsString());
					limits.put(id, new int[]{o.has("limit") && !o.get("limit").isJsonNull()
						? o.get("limit").getAsInt() : 100});
				}
				fetchPrices();
			});
		}
		else
		{
			fetchPrices();
		}
	}

	private void fetchPrices()
	{
		get("/latest", latestBody -> get("/5m", fiveBody ->
		{
			final JsonObject latest = gson.fromJson(latestBody, JsonObject.class).getAsJsonObject("data");
			final JsonObject five = gson.fromJson(fiveBody, JsonObject.class).getAsJsonObject("data");
			final List<Flip> flips = new ArrayList<>();
			for (Map.Entry<String, JsonElement> e : latest.entrySet())
			{
				final JsonObject q = e.getValue().getAsJsonObject();
				if (q.get("high").isJsonNull() || q.get("low").isJsonNull())
				{
					continue;
				}
				final int id = Integer.parseInt(e.getKey());
				final String name = names.get(id);
				if (name == null)
				{
					continue;
				}
				final int high = q.get("high").getAsInt();
				final int low = q.get("low").getAsInt();
				long vol = 0;
				final JsonElement v5 = five.get(e.getKey());
				if (v5 != null)
				{
					final JsonObject v = v5.getAsJsonObject();
					vol = (v.has("highPriceVolume") ? v.get("highPriceVolume").getAsLong() : 0)
						+ (v.has("lowPriceVolume") ? v.get("lowPriceVolume").getAsLong() : 0);
				}
				if (low < 100 || vol < 10)
				{
					continue;
				}
				final int buyAt = low + 1;
				final int sellAt = high - 1;
				final int net = sellAt - geTax(sellAt) - buyAt;
				if (net <= 0)
				{
					continue;
				}
				final double unitsHr = Math.min(limits.get(id)[0] / 4.0, vol * 12 * 0.10);
				flips.add(new Flip(name, buyAt, sellAt, net,
					net * 100.0 / buyAt, (long) (net * unitsHr)));
			}
			flips.sort(Comparator.comparingLong(f -> -f.gpHr));
			topFlips = flips.subList(0, Math.min(8, flips.size()));
			log.info("flip scan: {} candidates, top {}", flips.size(), topFlips.size());
		}));
	}

	private void get(String path, java.util.function.Consumer<String> onBody)
	{
		final Request req = new Request.Builder()
			.url(API + path)
			.header("User-Agent", UA)
			.build();
		http.newCall(req).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("wiki price fetch failed: {}", path, e);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (Response r = response)
				{
					if (r.isSuccessful() && r.body() != null)
					{
						onBody.accept(r.body().string());
					}
				}
				catch (Exception ex)
				{
					log.warn("flip parse failed: {}", path, ex);
				}
			}
		});
	}
}
