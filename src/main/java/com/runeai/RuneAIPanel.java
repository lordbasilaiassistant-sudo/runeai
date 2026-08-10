package com.runeai;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.Cursor;
import java.awt.Dimension;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;

public class RuneAIPanel extends PluginPanel
{
	private static final Color ACCENT = new Color(0, 180, 255);
	private static final Color OK_GREEN = new Color(0, 200, 120);
	private static final Color TRAP = new Color(190, 140, 255);
	private static final Color ALERT = new Color(255, 140, 40);
	private static final Color LOSS = new Color(255, 90, 90);

	private final JLabel stateValue = new JLabel("STARTING");
	private final JLabel playerValue = new JLabel("—");
	private final JLabel npcValue = new JLabel("0");
	private final JLabel playersValue = new JLabel("0");
	private final JLabel eventsValue = new JLabel("0");
	private final JLabel activityValue = new JLabel("—");
	private final JLabel pnlValue = new JLabel("0 gp");
	private final JLabel bondValue = new JLabel("—");
	private final JLabel flipPnlValue = new JLabel("0 gp");
	private final JPanel flipsBox = new JPanel();
	private final JPanel trapsBox = new JPanel();
	private final JLabel laneLine = new JLabel("no lane results yet");
	private final JLabel trapTitle = new JLabel("Overnight trap board");
	private final JPanel clogGrid = new JPanel();
	private final JLabel clogTitle = new JLabel("Trade log · 0 items");
	private int clogCount;
	private final JLabel scoreLine = new JLabel("first session — this one sets the bar");
	private final JLabel auditLine = new JLabel("ledger unaudited — open the GE trade history");
	private final JPanel scoreSection = new JPanel();
	private final JPanel itemsBox = new JPanel();
	private final JPanel itemsSection = new JPanel();
	private final JPanel anomalyBox = new JPanel();
	private final JPanel anomalySection = new JPanel();

	public RuneAIPanel()
	{
		super();
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		final JPanel container = new JPanel();
		container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));
		container.setBackground(ColorScheme.DARK_GRAY_COLOR);

		final JLabel title = new JLabel("RuneAI");
		title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
		title.setForeground(ACCENT);
		title.setAlignmentX(LEFT_ALIGNMENT);

		final JLabel subtitle = new JLabel("data layer active ✓");
		subtitle.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
		subtitle.setForeground(OK_GREEN);
		subtitle.setAlignmentX(LEFT_ALIGNMENT);

		container.add(title);
		container.add(subtitle);
		container.add(Box.createVerticalStrut(12));

		final JPanel card = new JPanel(new GridLayout(0, 1, 0, 6));
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 3, 0, 0, ACCENT),
			BorderFactory.createEmptyBorder(10, 10, 10, 10)));
		card.setAlignmentX(LEFT_ALIGNMENT);

		card.add(row("Game state", stateValue));
		card.add(row("Player", playerValue));
		card.add(row("Activity", activityValue));
		card.add(row("Session P&L", pnlValue));
		card.add(row("Flip P&L", flipPnlValue));
		card.add(row("Bond fund", bondValue));
		card.add(row("NPCs loaded", npcValue));
		card.add(row("Players loaded", playersValue));
		card.add(row("Events logged", eventsValue));

		container.add(card);
		container.add(Box.createVerticalStrut(12));

		// beat your last session: the only comparison that grades a session against
		// something it can actually be judged by — this player's own past sessions
		scoreLine.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
		scoreLine.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		scoreLine.setAlignmentX(LEFT_ALIGNMENT);
		scoreLine.setToolTipText("<html>Realized flip profit this session vs your last one and your best.<br>"
			+ "gp/h is measured over ACTIVE time — first offer placed to now.</html>");
		// the audit belongs next to the number it audits
		auditLine.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
		auditLine.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		auditLine.setAlignmentX(LEFT_ALIGNMENT);
		final JPanel scoreBody = new JPanel();
		scoreBody.setLayout(new BoxLayout(scoreBody, BoxLayout.Y_AXIS));
		scoreBody.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		scoreBody.setAlignmentX(LEFT_ALIGNMENT);
		scoreBody.add(scoreLine);
		scoreBody.add(Box.createVerticalStrut(6));
		scoreBody.add(auditLine);
		buildSection(scoreSection, "Session scoreboard", OK_GREEN, scoreBody);
		container.add(scoreSection);
		container.add(Box.createVerticalStrut(12));

		// blue moons: reported, never traded on. The judgment stays with the human
		anomalyBox.setLayout(new BoxLayout(anomalyBox, BoxLayout.Y_AXIS));
		final JLabel noAnomalies = new JLabel("<html>market normal — nothing has<br>ripped since you logged in</html>");
		noAnomalies.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		noAnomalies.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
		noAnomalies.setAlignmentX(LEFT_ALIGNMENT);
		anomalyBox.add(noAnomalies);
		buildSection(anomalySection, "Anomaly watch", ALERT, anomalyBox);
		anomalySection.setToolTipText("<html>Big moves on real volume, on both sides of the book.<br>"
			+ "What it is — pump, panic, ban wave — is a human call.</html>");
		container.add(anomalySection);
		container.add(Box.createVerticalStrut(12));

		// live GE flip suggestions (tax-aware, from wiki prices API)
		final JLabel flipTitle = new JLabel("Top GE flips (live)");
		flipTitle.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
		flipTitle.setForeground(ACCENT);
		flipTitle.setAlignmentX(LEFT_ALIGNMENT);
		container.add(flipTitle);
		container.add(Box.createVerticalStrut(4));

		flipsBox.setLayout(new BoxLayout(flipsBox, BoxLayout.Y_AXIS));
		flipsBox.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		flipsBox.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 3, 0, 0, new Color(255, 200, 0)),
			BorderFactory.createEmptyBorder(8, 8, 8, 8)));
		flipsBox.setAlignmentX(LEFT_ALIGNMENT);
		final JLabel loading = new JLabel("fetching prices…");
		loading.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		loading.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
		flipsBox.add(loading);
		container.add(flipsBox);
		container.add(Box.createVerticalStrut(4));

		// two lanes, two scoreboards — a hail-mary is never graded on quick-flip
		// metrics, so its wins and its dead slots are counted separately
		laneLine.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
		laneLine.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		laneLine.setAlignmentX(LEFT_ALIGNMENT);
		laneLine.setToolTipText("Fills, win rate and realised gp, counted per lane");
		container.add(laneLine);
		container.add(Box.createVerticalStrut(12));

		// what each item has actually paid THIS player — the ranker's opinion is a
		// forecast, this is the receipt
		itemsBox.setLayout(new BoxLayout(itemsBox, BoxLayout.Y_AXIS));
		final JLabel noItems = new JLabel("<html>no flips recorded yet —<br>results appear as you trade</html>");
		noItems.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		noItems.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
		noItems.setAlignmentX(LEFT_ALIGNMENT);
		itemsBox.add(noItems);
		buildSection(itemsSection, "Per-item results", ACCENT, itemsBox);
		itemsSection.setToolTipText("Fills, average fill time and realised gp per item, from your own trades");
		container.add(itemsSection);
		container.add(Box.createVerticalStrut(12));

		// the hail-mary board — cheap asks parked under the numbers whales type
		trapTitle.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
		trapTitle.setForeground(TRAP);
		trapTitle.setAlignmentX(LEFT_ALIGNMENT);
		trapTitle.setToolTipText("Run sim/whale_trap_report.py to refresh from your anomaly log");
		container.add(trapTitle);
		container.add(Box.createVerticalStrut(4));
		trapsBox.setLayout(new BoxLayout(trapsBox, BoxLayout.Y_AXIS));
		trapsBox.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		trapsBox.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 3, 0, 0, TRAP),
			BorderFactory.createEmptyBorder(8, 8, 8, 8)));
		trapsBox.setAlignmentX(LEFT_ALIGNMENT);
		final JLabel noTraps = new JLabel("<html>no board yet — run<br>sim/whale_trap_report.py</html>");
		noTraps.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		noTraps.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
		trapsBox.add(noTraps);
		container.add(trapsBox);
		container.add(Box.createVerticalStrut(12));

		final JLabel footer = new JLabel("<html>Recording everything →<br>.runelite\\runeai\\</html>");
		footer.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
		footer.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		footer.setAlignmentX(LEFT_ALIGNMENT);
		container.add(footer);
		container.add(Box.createVerticalStrut(14));

		final JButton kofi = new JButton("♥  Support us on Ko-fi");
		kofi.setBackground(new Color(255, 94, 91));
		kofi.setForeground(Color.WHITE);
		kofi.setFocusPainted(false);
		kofi.setBorderPainted(false);
		kofi.setOpaque(true);
		kofi.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
		kofi.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		kofi.setAlignmentX(LEFT_ALIGNMENT);
		kofi.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
		kofi.setToolTipText("One-time or monthly — you pick. Keeps RuneAI free.");
		kofi.addActionListener(e -> LinkBrowser.browse("https://ko-fi.com/broketobuilt"));
		kofi.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseEntered(java.awt.event.MouseEvent e)
			{
				kofi.setBackground(new Color(255, 122, 119));
			}

			@Override
			public void mouseExited(java.awt.event.MouseEvent e)
			{
				kofi.setBackground(new Color(255, 94, 91));
			}
		});
		container.add(kofi);
		container.add(Box.createVerticalStrut(14));

		clogTitle.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
		clogTitle.setForeground(ACCENT);
		clogTitle.setAlignmentX(LEFT_ALIGNMENT);
		container.add(clogTitle);
		container.add(Box.createVerticalStrut(4));
		clogGrid.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 3, 3));
		clogGrid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		clogGrid.setAlignmentX(LEFT_ALIGNMENT);
		container.add(clogGrid);

		add(container, BorderLayout.NORTH);
	}

	/**
	 * Title + accented body, laid out into {@code holder} so one
	 * {@code setVisible} hides the whole section when its config toggle is off.
	 */
	private void buildSection(JPanel holder, String titleText, Color accent, java.awt.Component body)
	{
		holder.setLayout(new BoxLayout(holder, BoxLayout.Y_AXIS));
		holder.setBackground(ColorScheme.DARK_GRAY_COLOR);
		holder.setAlignmentX(LEFT_ALIGNMENT);

		final JLabel t = new JLabel(titleText);
		t.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
		t.setForeground(accent);
		t.setAlignmentX(LEFT_ALIGNMENT);
		holder.add(t);
		holder.add(Box.createVerticalStrut(4));

		final JPanel box = new JPanel();
		box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
		box.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		box.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 3, 0, 0, accent),
			BorderFactory.createEmptyBorder(8, 8, 8, 8)));
		box.setAlignmentX(LEFT_ALIGNMENT);
		box.add(body);
		holder.add(box);
	}

	private JPanel row(String label, JLabel value)
	{
		final JPanel p = new JPanel(new BorderLayout());
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		final JLabel l = new JLabel(label);
		l.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		value.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
		value.setForeground(Color.WHITE);

		p.add(l, BorderLayout.WEST);
		p.add(value, BorderLayout.EAST);
		return p;
	}

	public void setGameState(String state)
	{
		SwingUtilities.invokeLater(() -> stateValue.setText(state));
	}

	public void setPlayer(String name)
	{
		SwingUtilities.invokeLater(() ->
		{
			playerValue.setText(name == null ? "—" : name);
			playerValue.setForeground(name == null ? Color.WHITE : OK_GREEN);
		});
	}

	public void setPnl(long pnl)
	{
		SwingUtilities.invokeLater(() ->
		{
			pnlValue.setText(String.format("%,d gp", pnl));
			pnlValue.setForeground(pnl > 0 ? OK_GREEN : pnl < 0 ? LOSS : Color.WHITE);
		});
	}

	public void setFlipPnl(long pnl)
	{
		SwingUtilities.invokeLater(() ->
		{
			flipPnlValue.setText(String.format("%,d gp", pnl));
			flipPnlValue.setForeground(pnl > 0 ? OK_GREEN : pnl < 0 ? LOSS : Color.WHITE);
		});
	}

	public void setFlips(java.util.List<FlipService.Flip> flips)
	{
		SwingUtilities.invokeLater(() ->
		{
			if (flips == null || flips.isEmpty())
			{
				return;
			}
			flipsBox.removeAll();
			for (FlipService.Flip f : flips.subList(0, Math.min(5, flips.size())))
			{
				final JLabel name = new JLabel(f.getName());
				name.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
				name.setForeground(Color.WHITE);
				name.setAlignmentX(LEFT_ALIGNMENT);
				final JLabel line = new JLabel(String.format(
					"buy %,d → sell %,d   +%,d (%.1f%%)  ~%ds",
					f.getBuyAt(), f.getSellAt(), f.getNet(), f.getRoi(), f.getCycleSecs()));
				line.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
				line.setForeground(new Color(255, 200, 0));
				line.setAlignmentX(LEFT_ALIGNMENT);
				flipsBox.add(name);
				flipsBox.add(line);
				flipsBox.add(Box.createVerticalStrut(5));
			}
			flipsBox.revalidate();
			flipsBox.repaint();
		});
	}

	/** Per-lane scoreboard: each lane graded on its own results, never the other's. */
	public void setLanes(ItemMemory.Lane quick, ItemMemory.Lane overnight)
	{
		SwingUtilities.invokeLater(() ->
		{
			if (quick.getFills() + overnight.getFills() + quick.getStalls() + overnight.getStalls() == 0)
			{
				return;
			}
			laneLine.setText(String.format("<html>QUICK %s<br>OVERNIGHT %s</html>",
				laneSummary(quick), laneSummary(overnight)));
		});
	}

	private static String laneSummary(ItemMemory.Lane l)
	{
		final int graded = l.getWins() + l.getLosses();
		final String win = graded > 0 ? String.format("%d%% win", l.getWins() * 100 / graded) : "no sells yet";
		return String.format("%d fills · %d pulled · %s · %+,d gp%s",
			l.getFills(), l.getStalls(), win, l.getTotalProfit(),
			l.getEwmaFillSecs() > 0 ? String.format(" · ~%.0fs", l.getEwmaFillSecs()) : "");
	}

	/** Show or hide the optional sections with their config toggles. */
	public void setViewsEnabled(boolean sessionScore, boolean itemStats, boolean anomalies)
	{
		SwingUtilities.invokeLater(() ->
		{
			scoreSection.setVisible(sessionScore);
			itemsSection.setVisible(itemStats);
			anomalySection.setVisible(anomalies);
		});
	}

	/**
	 * The anomaly feed, newest first. Each entry is one already-formatted line
	 * plus the detail the player needs to make the call themselves; the panel
	 * decides nothing here beyond which way to colour the arrow.
	 */
	public void setAnomalies(java.util.List<String[]> entries)
	{
		SwingUtilities.invokeLater(() ->
		{
			if (entries == null || entries.isEmpty())
			{
				return;
			}
			anomalyBox.removeAll();
			for (String[] e : entries)
			{
				// {headline, detail, "up"|"down", "held"|""}
				final JLabel head = new JLabel(e[0]);
				head.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
				head.setForeground("up".equals(e[2]) ? OK_GREEN : LOSS);
				head.setAlignmentX(LEFT_ALIGNMENT);
				final JLabel detail = new JLabel(e[1]);
				detail.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
				detail.setForeground("held".equals(e[3]) ? ALERT : ColorScheme.LIGHT_GRAY_COLOR);
				detail.setAlignmentX(LEFT_ALIGNMENT);
				anomalyBox.add(head);
				anomalyBox.add(detail);
				anomalyBox.add(Box.createVerticalStrut(5));
			}
			anomalyBox.revalidate();
			anomalyBox.repaint();
		});
	}

	/**
	 * This session against the record book. Deliberately states the gap in gp
	 * rather than a verdict — "1,240 to go" is something you can act on, "worse
	 * than last time" is not.
	 */
	public void setSessionScore(SessionHistory.Scoreboard s)
	{
		SwingUtilities.invokeLater(() ->
		{
			if (s.getRanked() == 0)
			{
				scoreLine.setText(String.format(
					"<html>THIS  %s gp · %s/h<br>"
						+ "<font color='#8a8a8a'>first recorded session — this one sets the bar</font></html>",
					signed(s.getCurPnl()), compact(s.getCurGpHr())));
				scoreLine.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				return;
			}
			final String verdict = s.getToBeatBest() == 0
				? "<font color='#ffc800'>BEST SESSION ON RECORD</font>"
				: s.getToBeatLast() == 0
					? String.format("<font color='#00c878'>beating last by %,d</font> · %,d to your best",
						s.getCurPnl() - s.getLastPnl(), s.getToBeatBest())
					: String.format("%,d to beat last session", s.getToBeatLast());
			scoreLine.setText(String.format(
				"<html>THIS  %s gp · %s/h<br>LAST  %s gp · %s/h<br>BEST  %s gp · %s/h<br>"
					+ "%s<br><font color='#8a8a8a'>ahead of %d of %d recorded sessions</font></html>",
				signed(s.getCurPnl()), compact(s.getCurGpHr()),
				signed(s.getLastPnl()), compact(s.getLastGpHr()),
				signed(s.getBestPnl()), compact(s.getBestGpHr()),
				verdict, s.getBeaten(), s.getRanked()));
			scoreLine.setForeground(Color.WHITE);
		});
	}

	/**
	 * What the game's own trade history said about our ledger. Green only when
	 * the two agreed — a check nobody can fail is not a check.
	 */
	public void setAudit(String summary, boolean clean, String tooltip)
	{
		SwingUtilities.invokeLater(() ->
		{
			auditLine.setText("<html>audit · " + summary + "</html>");
			auditLine.setForeground(clean ? ColorScheme.LIGHT_GRAY_COLOR : ALERT);
			auditLine.setToolTipText(tooltip);
		});
	}

	/** Per-item receipts: fills, measured fill time, realised gp, split by lane. */
	public void setItemStats(java.util.List<ItemMemory.ItemStat> stats)
	{
		SwingUtilities.invokeLater(() ->
		{
			if (stats == null || stats.isEmpty())
			{
				return;
			}
			itemsBox.removeAll();
			for (ItemMemory.ItemStat s : stats)
			{
				final JLabel name = new JLabel(s.getName());
				name.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
				name.setForeground(Color.WHITE);
				name.setAlignmentX(LEFT_ALIGNMENT);

				final StringBuilder sub = new StringBuilder();
				sub.append(s.getFills()).append(s.getFills() == 1 ? " flip" : " flips");
				if (s.getStalls() > 0)
				{
					sub.append(" · ").append(s.getStalls()).append(" pulled");
				}
				sub.append(" · ").append(s.getAvgFillSecs() >= 0
					? "~" + s.getAvgFillSecs() + "s" : "untimed");
				sub.append(" · ").append(String.format("%+,d", s.getTotalProfit()));

				final JLabel line = new JLabel(sub.toString());
				line.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
				line.setForeground(s.getTotalProfit() > 0 ? OK_GREEN
					: s.getTotalProfit() < 0 ? LOSS : ColorScheme.LIGHT_GRAY_COLOR);
				line.setAlignmentX(LEFT_ALIGNMENT);
				// the lane split only earns a line when the item actually ran in
				// both lanes — otherwise it is a zero pretending to be information
				if (s.getQuickFills() > 0 && s.getLongFills() > 0)
				{
					line.setToolTipText(String.format("QUICK %d fills %+,d gp · OVERNIGHT %d fills %+,d gp",
						s.getQuickFills(), s.getQuickProfit(), s.getLongFills(), s.getLongProfit()));
				}
				itemsBox.add(name);
				itemsBox.add(line);
				if (s.getQuickFills() > 0 && s.getLongFills() > 0)
				{
					final JLabel lanes = new JLabel(String.format("quick %d · overnight %d",
						s.getQuickFills(), s.getLongFills()));
					lanes.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 9));
					lanes.setForeground(TRAP);
					lanes.setAlignmentX(LEFT_ALIGNMENT);
					itemsBox.add(lanes);
				}
				itemsBox.add(Box.createVerticalStrut(5));
			}
			itemsBox.revalidate();
			itemsBox.repaint();
		});
	}

	private static String signed(long v)
	{
		return String.format("%+,d", v);
	}

	/** Narrow-panel gp: 24.5k / 1.2m, because the sidebar is ~225px wide. */
	private static String compact(long v)
	{
		final long a = Math.abs(v);
		final String sign = v < 0 ? "-" : "";
		if (a >= 1_000_000)
		{
			return String.format("%s%.1fm", sign, a / 1_000_000.0);
		}
		if (a >= 1_000)
		{
			return String.format("%s%.1fk", sign, a / 1_000.0);
		}
		return String.valueOf(v);
	}

	public void setTraps(java.util.List<TrapBoard.Pick> picks, boolean headingOffline)
	{
		SwingUtilities.invokeLater(() ->
		{
			trapTitle.setText(headingOffline
				? "Heading offline · fill every slot"
				: "Overnight trap board");
			if (picks == null || picks.isEmpty())
			{
				return;
			}
			trapsBox.removeAll();
			for (TrapBoard.Pick p : picks)
			{
				final JLabel name = new JLabel(String.format("%s  %.0fx", p.getName(), p.getPayoffX()));
				name.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
				name.setForeground(Color.WHITE);
				name.setAlignmentX(LEFT_ALIGNMENT);
				final JLabel line = new JLabel(String.format("buy %,d @ %,d → list @ %,d",
					p.getQty(), p.getBuyAt(), p.getListAt()));
				line.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
				line.setForeground(TRAP);
				line.setAlignmentX(LEFT_ALIGNMENT);
				line.setToolTipText(p.getCorridor() + " — park it and log off; it fills or it doesn't");
				trapsBox.add(name);
				trapsBox.add(line);
				trapsBox.add(Box.createVerticalStrut(5));
			}
			trapsBox.revalidate();
			trapsBox.repaint();
		});
	}

	public void addCollected(String name, net.runelite.client.util.AsyncBufferedImage img)
	{
		SwingUtilities.invokeLater(() ->
		{
			final JLabel icon = new JLabel();
			icon.setToolTipText(name);
			img.addTo(icon);
			clogGrid.add(icon);
			clogTitle.setText("Trade log · " + (++clogCount) + " items");
			clogGrid.revalidate();
		});
	}

	public void setBond(boolean members, long worth, long bondPrice, boolean bankKnown)
	{
		SwingUtilities.invokeLater(() ->
		{
			if (members)
			{
				bondValue.setText("members ✓");
				bondValue.setForeground(OK_GREEN);
				return;
			}
			if (bondPrice <= 0)
			{
				bondValue.setText("—");
				return;
			}
			final int pct = (int) Math.min(100, worth * 100 / bondPrice);
			bondValue.setText(String.format("%d%%%s of %,dk", pct, bankKnown ? "" : "*", bondPrice / 1000));
			bondValue.setToolTipText(bankKnown
				? String.format("Total worth %,d gp vs bond %,d gp", worth, bondPrice)
				: "Open your bank once to count everything you own");
			bondValue.setForeground(pct >= 100 ? OK_GREEN : ACCENT);
		});
	}

	public void setActivity(String activity)
	{
		SwingUtilities.invokeLater(() ->
		{
			activityValue.setText(activity);
			activityValue.setForeground("—".equals(activity) ? Color.WHITE : ACCENT);
		});
	}

	public void setCounts(int npcs, int players, long events)
	{
		SwingUtilities.invokeLater(() ->
		{
			npcValue.setText(String.valueOf(npcs));
			playersValue.setText(String.valueOf(players));
			eventsValue.setText(String.valueOf(events));
		});
	}

	static BufferedImage createIcon()
	{
		final int s = 16;
		final BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setPaint(new GradientPaint(0, 0, ACCENT, s, s, new Color(90, 60, 255)));
		g.fillRoundRect(0, 0, s, s, 6, 6);
		g.setColor(Color.WHITE);
		g.setStroke(new BasicStroke(1.6f));
		g.drawLine(4, 12, 8, 3);
		g.drawLine(8, 3, 12, 12);
		g.drawLine(6, 9, 10, 9);
		g.dispose();
		return img;
	}
}
