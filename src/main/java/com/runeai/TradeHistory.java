package com.runeai;

import java.util.ArrayList;
import java.util.List;
import lombok.Value;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;

/**
 * The game's own receipts, used to audit ours.
 *
 * <p>RuneAI books profit by delta accounting off {@code GrandExchangeOfferChanged}:
 * quantity moved times the price we think the offer was at, minus a tax we compute
 * ourselves. Every part of that is an ASSUMPTION about what the server did. The
 * in-game Trade History interface is the server's version of the same events, so
 * when the player opens it we read it and diff the two. Measurement beats
 * assumption — and a ledger nobody audits is a ledger that drifts.
 *
 * <p>Strictly READ-ONLY. This walks widgets that are already on screen because the
 * player opened them. It sends nothing, clicks nothing, and adds no menu entry.
 *
 * <p><b>The parse is the uncertain half and is written to admit it.</b> The item id
 * and stack size come straight off the widget model and are solid; direction and
 * price have to be read out of display text whose exact wording is only knowable
 * from a live client. So {@link #parseRow} returns null rather than guessing, the
 * caller counts what it could not read, and every raw row is written to the event
 * log the first time the interface is opened in a session. The next revision of
 * this parser should come from that log, not from another guess.
 */
class TradeHistory
{
	/** How many rows we will look at, however long the list is. */
	private static final int MAX_ROWS = 30;

	/** One completed trade, from either side of the audit. */
	@Value
	static class Entry
	{
		int itemId;
		int qty;
		long unitPrice;
		boolean bought;
	}

	/** A row as read, parsed or not, with the text it was read from. */
	@Value
	static class Row
	{
		int itemId;
		int qty;
		Entry parsed;      // null when the direction or the price could not be read
		String rawText;    // what the widgets actually said, for the log
	}

	enum Kind
	{
		/** The game has a trade we never booked at all. */
		UNBOOKED,
		/** We booked this trade at a different price than the game charged. */
		PRICE,
		/** Same item and side, different quantity. */
		QTY
	}

	@Value
	static class Discrepancy
	{
		int itemId;
		Kind kind;
		/** Correction to OUR profit, signed: positive means we under-booked. */
		long gpDelta;
		String detail;
	}

	// ================= reading the interface =================

	/** True while the player has the Trade History interface open. */
	static boolean isOpen(Client client)
	{
		final Widget w = client.getWidget(InterfaceID.GeHistory.LIST);
		return w != null && !w.isHidden();
	}

	/**
	 * Read every row the interface is currently showing. Client thread only —
	 * it walks live widgets.
	 *
	 * <p>Two list shapes are handled because which one the cs2 builds is not
	 * something we get to assume: one container widget per row, or a flat run of
	 * cells where each item sprite starts a new row.
	 */
	static List<Row> read(Client client)
	{
		final Widget list = client.getWidget(InterfaceID.GeHistory.LIST);
		if (list == null || list.isHidden())
		{
			return List.of();
		}
		final List<Widget> cells = new ArrayList<>();
		final List<List<Widget>> containers = new ArrayList<>();
		for (Widget child : childrenOf(list))
		{
			final List<Widget> inner = childrenOf(child);
			if (!inner.isEmpty())
			{
				containers.add(inner);
			}
			else
			{
				cells.add(child);
			}
		}
		final List<Row> rows = new ArrayList<>();
		if (!containers.isEmpty())
		{
			for (List<Widget> row : containers)
			{
				addRow(rows, row);
			}
		}
		else
		{
			// FLAT RUN — and the cell order is the thing to get right. Measured from
			// a live client (event log 2026-08-14): the widgets arrive as
			//   [direction_0, name_0, sprite_0, price_0, direction_1, name_1, sprite_1, ...]
			// The old grouping opened a row at each sprite, which glued row N's price
			// to row N+1's direction and name — every row then disagreed with the
			// ledger and a 29-row history audited as a +12.2B "hole". So: text cells
			// BEFORE a sprite belong to that sprite's row; text cells after it are
			// the price block until the next direction/name text shows up.
			final List<Widget> pending = new ArrayList<>();
			List<Widget> current = null;
			for (Widget c : cells)
			{
				if (c.getItemId() > 0)
				{
					if (current != null)
					{
						addRow(rows, current);
					}
					current = new ArrayList<>(pending);
					pending.clear();
					current.add(c);
					continue;
				}
				final String t = plain(c.getText());
				if (t.isEmpty())
				{
					continue;
				}
				// a direction or name cell after a sprite belongs to the NEXT row;
				// coin text is this row's price block
				if (current != null && t.contains("coin"))
				{
					current.add(c);
				}
				else
				{
					pending.add(c);
				}
			}
			if (current != null)
			{
				addRow(rows, current);
			}
		}
		return rows.size() > MAX_ROWS ? rows.subList(0, MAX_ROWS) : rows;
	}

	private static void addRow(List<Row> rows, List<Widget> cells)
	{
		int itemId = -1;
		int qty = 0;
		final List<String> texts = new ArrayList<>();
		final StringBuilder raw = new StringBuilder();
		for (Widget c : cells)
		{
			if (c.getItemId() > 0 && itemId <= 0)
			{
				itemId = c.getItemId();
				qty = c.getItemQuantity();
			}
			final String t = c.getText();
			if (t != null && !t.isEmpty())
			{
				texts.add(t);
				if (raw.length() > 0)
				{
					raw.append(" | ");
				}
				raw.append(t);
			}
		}
		if (itemId <= 0 && texts.isEmpty())
		{
			return; // spacer/decoration, not a trade
		}
		rows.add(new Row(itemId, qty, parseRow(itemId, qty, texts), raw.toString()));
	}

	private static List<Widget> childrenOf(Widget w)
	{
		final List<Widget> out = new ArrayList<>();
		for (Widget[] set : new Widget[][]{w.getDynamicChildren(), w.getStaticChildren(), w.getNestedChildren()})
		{
			if (set == null)
			{
				continue;
			}
			for (Widget c : set)
			{
				if (c != null)
				{
					out.add(c);
				}
			}
		}
		return out;
	}

	// ================= parsing (pure) =================

	/** Strip the client's colour/formatting tags and normalise for matching. */
	static String plain(String s)
	{
		if (s == null)
		{
			return "";
		}
		// tags out, then every flavour of whitespace collapsed to one. The client
		// uses non-breaking spaces in places and \s does not match those, so a
		// " for " or "x " test would silently miss the rows it was written for.
		return s.replaceAll("<[^>]*>", " ")
			.replaceAll("[\\s\\xA0]+", " ")
			.trim()
			.toLowerCase();
	}

	/** Every run of digits (with thousands separators) in a line, in order. */
	static List<Long> numbers(String s)
	{
		final List<Long> out = new ArrayList<>();
		final java.util.regex.Matcher m =
			java.util.regex.Pattern.compile("\\d[\\d,]*").matcher(s == null ? "" : s);
		while (m.find())
		{
			try
			{
				out.add(Long.parseLong(m.group().replace(",", "")));
			}
			catch (NumberFormatException ignored)
			{
				// a number too big to be a price is not a price
			}
		}
		return out;
	}

	/**
	 * One row's display text into a trade, or null when it cannot be read.
	 *
	 * <p>Returning null on doubt is deliberate. A guessed price would be diffed
	 * against a real one and reported to the player as a discrepancy in THEIR
	 * ledger, which is worse than admitting the row was unreadable.
	 */
	static Entry parseRow(int itemId, int itemQty, List<String> texts)
	{
		if (itemId <= 0 || texts == null)
		{
			return null;
		}
		Boolean bought = null;
		long each = -1;
		long total = -1;
		long qty = itemQty;
		for (String rawText : texts)
		{
			final String t = plain(rawText);
			if (t.isEmpty())
			{
				continue;
			}
			if (t.contains("bought"))
			{
				bought = Boolean.TRUE;
			}
			else if (t.contains("sold"))
			{
				bought = Boolean.FALSE;
			}
			final List<Long> nums = numbers(t);
			if (nums.isEmpty())
			{
				continue;
			}
			// the live client's price block reads "994,923 coins (1,014,732 - 19,809)
			// = 1,557 each": the FIRST number is the row's total coins, and the
			// per-unit price is the number immediately before "each" — grabbing
			// nums.get(0) for "each" booked totals as unit prices and made a 29-row
			// history audit as +12.2B
			if (t.contains("coin"))
			{
				total = nums.get(0);
			}
			final java.util.regex.Matcher ea = java.util.regex.Pattern
				.compile("([\\d,]+)(?: coins)? (?:each|ea\\b|per\\b)").matcher(t);
			if (ea.find())
			{
				each = Long.parseLong(ea.group(1).replace(",", ""));
			}
			if (qty <= 0)
			{
				// take the number attached to the "x", not the line's first number —
				// an item name like "Trailblazer top (t2) x 500" leads with the 2
				final java.util.regex.Matcher xq = java.util.regex.Pattern
					.compile("(?:^|[^\\d])x\\s*([\\d,]+)|([\\d,]+)\\s*x(?:[^\\w]|$)").matcher(t);
				if (xq.find())
				{
					qty = Long.parseLong((xq.group(1) != null ? xq.group(1) : xq.group(2)).replace(",", ""));
				}
			}
		}
		if (bought == null || qty <= 0)
		{
			return null;
		}
		final long unit = each > 0 ? each : total > 0 ? total / qty : -1;
		if (unit <= 0)
		{
			return null;
		}
		return new Entry(itemId, (int) qty, unit, bought);
	}

	// ================= reconciliation (pure) =================

	/**
	 * Does the game's price agree with the one we booked?
	 *
	 * <p>A sell is allowed to agree either way round, because whether the
	 * interface quotes the ask or the coins that actually landed after the 2% is
	 * exactly the kind of thing we are not entitled to assume. Which convention
	 * matched is reported, so one real trade settles the question by measurement.
	 */
	static boolean priceAgrees(long gamePrice, long bookedPrice, boolean bought)
	{
		// ±1gp tolerance on the sell convention: the interface's "each" divides a
		// post-tax TOTAL by the quantity while we subtract a per-unit tax, and the
		// two floors can land one gp apart. A rounding artifact is not a discrepancy.
		return gamePrice == bookedPrice
			|| (!bought && bookedPrice > 0 && bookedPrice <= Integer.MAX_VALUE
				&& Math.abs(gamePrice - (bookedPrice - FlipService.geTax((int) bookedPrice))) <= 1);
	}

	/**
	 * Diff the game's rows against ours. Pure: both sides arrive as arguments.
	 *
	 * <p>{@code gpDelta} is always a correction to OUR profit, so the signs read
	 * the same way whichever side of the trade drifted: money the game says we
	 * made and we did not count is positive, money it says we spent and we did
	 * not count is negative.
	 */
	static List<Discrepancy> reconcile(List<Entry> game, List<Entry> booked)
	{
		final List<Entry> pool = new ArrayList<>(booked == null ? List.of() : booked);
		final List<Discrepancy> out = new ArrayList<>();
		for (Entry g : game == null ? List.<Entry>of() : game)
		{
			int exact = -1;
			int sameSide = -1;
			for (int i = 0; i < pool.size(); i++)
			{
				final Entry b = pool.get(i);
				if (b.getItemId() != g.getItemId() || b.isBought() != g.isBought())
				{
					continue;
				}
				if (b.getQty() == g.getQty())
				{
					exact = i;
					break;
				}
				if (sameSide < 0)
				{
					sameSide = i;
				}
			}
			if (exact >= 0)
			{
				final Entry b = pool.remove(exact);
				if (!priceAgrees(g.getUnitPrice(), b.getUnitPrice(), g.isBought()))
				{
					final long perUnit = g.getUnitPrice() - b.getUnitPrice();
					out.add(new Discrepancy(g.getItemId(), Kind.PRICE,
						(g.isBought() ? -1 : 1) * perUnit * g.getQty(),
						String.format("%d @ %,d booked, game says %,d",
							g.getQty(), b.getUnitPrice(), g.getUnitPrice())));
				}
				continue;
			}
			if (sameSide >= 0)
			{
				final Entry b = pool.remove(sameSide);
				out.add(new Discrepancy(g.getItemId(), Kind.QTY,
					(g.isBought() ? -1 : 1)
						* (g.getQty() * g.getUnitPrice() - b.getQty() * b.getUnitPrice()),
					String.format("booked %d @ %,d, game says %d @ %,d",
						b.getQty(), b.getUnitPrice(), g.getQty(), g.getUnitPrice())));
				continue;
			}
			out.add(new Discrepancy(g.getItemId(), Kind.UNBOOKED,
				(g.isBought() ? -1 : 1) * g.getQty() * g.getUnitPrice(),
				String.format("%s %d @ %,d, never booked",
					g.isBought() ? "bought" : "sold", g.getQty(), g.getUnitPrice())));
		}
		return out;
	}

	/**
	 * The discrepancies that are actually OURS, out of everything
	 * {@link #reconcile} found.
	 *
	 * <p>The game's Trade History reaches further back than our ledger does: it
	 * lists the account's last trades, while we only book offers we watched from
	 * placement, and only since the plugin was installed. Every row older than our
	 * first booked offer therefore comes back UNBOOKED, and reporting those to the
	 * player accuses their ledger of losing money it never had — a first audit
	 * would announce a multi-million-gp hole made entirely of trades from before
	 * RuneAI existed.
	 *
	 * <p>At most {@code gameRows - bookedRows} rows can predate us, so that many
	 * UNBOOKED findings are set aside. Anything past that count is a genuine gap
	 * and is still reported. Nothing is deleted — the caller logs the full diff
	 * either way; this only decides what is worth interrupting a human over.
	 *
	 * @return the reportable subset, in the order {@code reconcile} produced
	 */
	static List<Discrepancy> auditable(List<Discrepancy> diffs, int gameRows, int bookedRows)
	{
		if (diffs == null || diffs.isEmpty())
		{
			return List.of();
		}
		int budget = Math.max(0, gameRows - Math.max(0, bookedRows));
		// rows that predate the ledger are by definition the OLDEST, and the
		// interface lists most-recent-first (an order reconcile preserves), so
		// the budget excuses UNBOOKED findings from the tail up — spending it on
		// the head would excuse a genuinely missed recent trade and report a
		// pre-install one in its place
		final boolean[] excused = new boolean[diffs.size()];
		for (int i = diffs.size() - 1; i >= 0 && budget > 0; i--)
		{
			if (diffs.get(i).getKind() == Kind.UNBOOKED)
			{
				excused[i] = true;
				budget--;
			}
		}
		final List<Discrepancy> out = new ArrayList<>();
		for (int i = 0; i < diffs.size(); i++)
		{
			if (!excused[i])
			{
				out.add(diffs.get(i));
			}
		}
		return out;
	}

	/** How many findings {@link #auditable} set aside as older than our ledger. */
	static int predatingLedger(List<Discrepancy> diffs, int gameRows, int bookedRows)
	{
		return (diffs == null ? 0 : diffs.size()) - auditable(diffs, gameRows, bookedRows).size();
	}

	/** Net correction the audit says our P&L is off by. */
	static long netDelta(List<Discrepancy> ds)
	{
		long sum = 0;
		for (Discrepancy d : ds)
		{
			sum += d.getGpDelta();
		}
		return sum;
	}
}
