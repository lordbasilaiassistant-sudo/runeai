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
	private final JLabel trapTitle = new JLabel("Overnight trap board");
	private final JPanel clogGrid = new JPanel();
	private final JLabel clogTitle = new JLabel("Trade log · 0 items");
	private int clogCount;

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
			pnlValue.setForeground(pnl > 0 ? OK_GREEN : pnl < 0 ? new Color(255, 90, 90) : Color.WHITE);
		});
	}

	public void setFlipPnl(long pnl)
	{
		SwingUtilities.invokeLater(() ->
		{
			flipPnlValue.setText(String.format("%,d gp", pnl));
			flipPnlValue.setForeground(pnl > 0 ? OK_GREEN : pnl < 0 ? new Color(255, 90, 90) : Color.WHITE);
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
					"buy %,d → sell %,d   +%,d (%.1f%%)",
					f.getBuyAt(), f.getSellAt(), f.getNet(), f.getRoi()));
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

	public void setTraps(java.util.List<TrapBoard.Pick> picks)
	{
		SwingUtilities.invokeLater(() ->
		{
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
