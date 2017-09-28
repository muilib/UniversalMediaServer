/*
 * PS3 Media Server, for streaming any medias to your PS3.
 * Copyright (C) 2008  A.Brochard
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; version 2
 * of the License only.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package net.pms.encoders;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.StringTokenizer;
import javax.swing.JComponent;
import net.pms.PMS;
import net.pms.configuration.PmsConfiguration;
import net.pms.configuration.RendererConfiguration;
import net.pms.dlna.DLNAMediaAudio;
import net.pms.dlna.DLNAMediaInfo;
import net.pms.dlna.DLNAMediaOpenSubtitle;
import net.pms.dlna.DLNAMediaSubtitle;
import net.pms.dlna.DLNAResource;
import net.pms.external.ExternalFactory;
import net.pms.external.ExternalListener;
import net.pms.external.FinalizeTranscoderArgsListener;
import net.pms.formats.Format;
import net.pms.io.OutputParams;
import net.pms.io.ProcessWrapper;
import net.pms.util.Iso639;
import net.pms.util.SubtitleUtils;
import net.pms.util.UMSUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class Player {
	private static final Logger LOGGER = LoggerFactory.getLogger(Player.class);

	public static final int VIDEO_SIMPLEFILE_PLAYER = 0;
	public static final int AUDIO_SIMPLEFILE_PLAYER = 1;
	public static final int VIDEO_WEBSTREAM_PLAYER = 2;
	public static final int AUDIO_WEBSTREAM_PLAYER = 3;
	public static final int MISC_PLAYER = 4;
	public static final String NATIVE = "NATIVE";

	public abstract int purpose();
	public abstract JComponent config();
	public abstract String id();
	public abstract String name();
	public abstract int type();

	// FIXME this is an implementation detail (and not a very good one).
	// it's entirely up to engines how they construct their command lines.
	// need to get rid of this
	public abstract String[] args();

	public abstract String mimeType();
	public abstract String executable();
	protected static final PmsConfiguration _configuration = PMS.getConfiguration();
	protected PmsConfiguration configuration = _configuration;
	private static List<FinalizeTranscoderArgsListener> finalizeTranscoderArgsListeners = new ArrayList<>();

	public static void initializeFinalizeTranscoderArgsListeners() {
		for (ExternalListener listener : ExternalFactory.getExternalListeners()) {
			if (listener instanceof FinalizeTranscoderArgsListener) {
				finalizeTranscoderArgsListeners.add((FinalizeTranscoderArgsListener) listener);
			}
		}
	}

	public boolean avisynth() {
		return false;
	}

	public boolean excludeFormat(Format extension) {
		return false;
	}

	public boolean isPlayerCompatible(RendererConfiguration renderer) {
		return true;
	}

	public boolean isInternalSubtitlesSupported() {
		return true;
	}

	public boolean isExternalSubtitlesSupported() {
		return true;
	}

	public boolean isTimeSeekable() {
		return false;
	}

	/**
	 * Each engine capable of video hardware acceleration must override this
	 * method and set
	 * <p>
	 * <code>return true</code>.
	 *
	 * @return false
	 */
	public boolean isGPUAccelerationReady() {
		return false;
	}

	/**
	 * @deprecated Use {@link #launchTranscode(net.pms.dlna.DLNAResource, net.pms.dlna.DLNAMediaInfo, net.pms.io.OutputParams)} instead.
	 */
	@Deprecated
	public final ProcessWrapper launchTranscode(String filename, DLNAResource dlna, DLNAMediaInfo media, OutputParams params) throws IOException {
		return launchTranscode(dlna, media, params);
	}

	public abstract ProcessWrapper launchTranscode(
		DLNAResource dlna,
		DLNAMediaInfo media,
		OutputParams params
	) throws IOException;

	@Override
	public String toString() {
		return name();
	}

	// no need to pass Player as a parameter: it's the invocant
	@Deprecated
	protected String[] finalizeTranscoderArgs(
		Player player,
		String filename,
		DLNAResource dlna,
		DLNAMediaInfo media,
		OutputParams params,
		String[] cmdArgs
	) {
		return finalizeTranscoderArgs(
			filename,
			dlna,
			media,
			params,
			cmdArgs
		);
	}

	protected String[] finalizeTranscoderArgs(
		String filename,
		DLNAResource dlna,
		DLNAMediaInfo media,
		OutputParams params,
		String[] cmdArgs
	) {
		if (finalizeTranscoderArgsListeners.isEmpty()) {
			return cmdArgs;
		}
		// make it mutable
		List<String> cmdList = new ArrayList<>(Arrays.asList(cmdArgs));

		for (FinalizeTranscoderArgsListener listener : finalizeTranscoderArgsListeners) {
			try {
				cmdList = listener.finalizeTranscoderArgs(
					this,
					filename,
					dlna,
					media,
					params,
					cmdList
				);
			} catch (Throwable t) {
				LOGGER.error("Failed to call finalizeTranscoderArgs on listener of type \"{}\"", listener.getClass().getSimpleName(), t.getMessage());
				LOGGER.trace("", t);
			}
		}

		String[] cmdArray = new String[cmdList.size()];
		cmdList.toArray(cmdArray);
		return cmdArray;
	}

	/**
	 * @deprecated Use {@link #setAudioAndSubs(String fileName, DLNAMediaInfo media, OutputParams params)} instead.
	 */
	@Deprecated
	public void setAudioAndSubs(String fileName, DLNAMediaInfo media, OutputParams params, PmsConfiguration configuration) {
		setAudioAndSubs(fileName, media, params);
	}

	/**
	 * This method populates the supplied {@link OutputParams} object with the correct audio track (aid)
	 * and subtitles (sid), based on the given filename, its MediaInfo metadata and PMS configuration settings.
	 *
	 * @param fileName
	 * The file name used to determine the availability of subtitles.
	 * @param media
	 * The MediaInfo metadata for the file.
	 * @param params
	 * The parameters to populate.
	 */
	public static void setAudioAndSubs(String fileName, DLNAMediaInfo media, OutputParams params) {
		if (params == null) {
			return;
		}
		if (params.aid == null) {
			params.aid = resolveAudioStream(media, params.mediaRenderer);
		}

		if (params.sid != null && params.sid.getId() == -1) {
			LOGGER.trace("Don't want subtitles!");
			params.sid = null;
		} else if (params.sid instanceof DLNAMediaOpenSubtitle && params.sid.getExternalFile() != null) { //TODO: (Nad) Temp disable
			 // Check for live subtitles, the call to getExternalFile() will actually download the subtitle
			return;
		} else if (params.sid == null) {
			params.sid = resolveSubtitlesStream(fileName, media, params.mediaRenderer, params.aid == null ? null : params.aid.getLang(), false);
		}

//		setSubtitleOutputParameters(fileName, media, params);
	}

	/**
	 * This method populates figures out which audio track should be used based
	 * on {@link DLNAMediaInfo} metadata and configuration settings.
	 *
	 * @param media the {@link DLNAMediaInfo} metadata for the file.
	 * @param renderer the {@link RendererConfiguration} from which to get the
	 *            configuration or {@code null} to use the default
	 *            configuration.
	 * @return The resolved {@link DLNAMediaAudio}.
	 */
	public static DLNAMediaAudio resolveAudioStream(DLNAMediaInfo media, RendererConfiguration renderer) {
		// Use device-specific pms conf
		PmsConfiguration configuration = PMS.getConfiguration(renderer);
		if (media != null && media.getFirstAudioTrack() != null) {
			// check for preferred audio
			DLNAMediaAudio dtsTrack = null;
			StringTokenizer st = new StringTokenizer(configuration.getAudioLanguages(), ",");
			while (st.hasMoreTokens()) {
				String lang = st.nextToken().trim();
				LOGGER.trace("Looking for an audio track with language \"{}\"", lang);
				for (DLNAMediaAudio audio : media.getAudioTracksList()) {
					if (audio.matchCode(lang)) {
						LOGGER.trace("Matched audio track: {}", audio);
						return audio;
					}

					if (dtsTrack == null && audio.isDTS()) {
						dtsTrack = audio;
					}
				}
			}

			// preferred audio not found, take a default audio track, dts first if available
			if (dtsTrack != null) {
				LOGGER.trace("Preferring DTS audio track since no language match was found: {}", dtsTrack);
				return dtsTrack;
			}
			DLNAMediaAudio result = media.getFirstAudioTrack();
			LOGGER.trace("Using the first available audio track: {}", result);
			return result;
		}
		LOGGER.trace("Found no audio track");
		return null;
	}

	/**
	 * This method populates the supplied {@link OutputParams} object with the correct subtitles (sid)
	 * based on the given filename, its MediaInfo metadata and PMS configuration settings.
	 *
	 * TODO: Rewrite this crazy method to be more concise and logical.
	 *
	 * @param fileName
	 * The file name used to determine the availability of subtitles.
	 * @param media
	 * The MediaInfo metadata for the file.
	 * @param params
	 * The parameters to populate.
	 */
	//TODO: (Nad) Bug assuming that all media are files
	public static DLNAMediaSubtitle resolveSubtitlesStream(String fileName, DLNAMediaInfo media, RendererConfiguration renderer, String audioLanguage, boolean forceRefresh) {
		if (media == null ) {
			return null;
		}

		// Use device-specific pms conf
		PmsConfiguration configuration = PMS.getConfiguration(renderer);
		if (configuration.isDisableSubtitles()) {
			LOGGER.trace("Not resolving subtitles since subtitles are disabled");
			return null;
		}

		DLNAMediaSubtitle matchedSub = null;
		//TODO: (Nad) Verify that checking for subtitles here is good
		if (forceRefresh || !media.isExternalSubsParsed()) { //TODO: (Nad) isExternalSubtitlesExists, also checks alternative folder
			SubtitleUtils.registerExternalSubtitles(new File(fileName), media, forceRefresh);
		}

		if (media == null || !media.hasSubtitles()) { // There aren't subs for this media so skip checking for languages
			return null;
		}

		StringTokenizer st = new StringTokenizer(configuration.getAudioSubLanguages(), ";");

		/*
		 * Check for external and internal subtitles matching the user's
		 * language preferences
		 */
		boolean matchedInternalSubtitles = false;
		boolean matchedExternalSubtitles = false;
		while (st.hasMoreTokens()) {
			String pair = st.nextToken();
			if (pair.contains(",")) {
				String audio = pair.substring(0, pair.indexOf(','));
				String sub = pair.substring(pair.indexOf(',') + 1);
				audio = audio.trim();
				sub = sub.trim();
				if (audioLanguage != null && LOGGER.isTraceEnabled()) {
					LOGGER.trace(
						"Searching for a match for language \"{}\" with audio \"{}\" and subtitle \"{}\"",
						audioLanguage,
						audio,
						sub
					);
				}

				if (Iso639.isCodesMatching(audio, audioLanguage) || (audioLanguage != null && audio.equals("*"))) {
					if ("off".equals(sub)) {
						/*
						 * Ignore the "off" language for external subtitles if the user setting is enabled
						 * TODO: Prioritize multiple external subtitles properly instead of just taking the first one we load
						 */
						if (configuration.isForceExternalSubtitles()) {
							for (DLNAMediaSubtitle present_sub : media.getSubtitleTracksList()) {
								if (present_sub.isExternal()) {
									matchedSub = present_sub;
									matchedExternalSubtitles = true;
									LOGGER.trace("Ignoring the \"off\" language because external subtitles are enforced");
									break;
								}
							}
						} else {
							LOGGER.trace(
								"Not looking for non-forced subtitles since they are \"off\" for audio language \"{}\"",
								audio
							);
						}
					} else {
						for (DLNAMediaSubtitle present_sub : media.getSubtitleTracksList()) {
							if ("*".equals(sub) || present_sub.matchCode(sub)) {
								if (present_sub.isExternal()) {
									if (configuration.isAutoloadExternalSubtitles()) {
										// Subtitle is external and we want external subtitles, look no further
										matchedSub = present_sub;
										LOGGER.trace("Matched external subtitles track: {}", matchedSub);
										break;
									}
									// Subtitle is external but auto load external subtitles is disabled, keep searching
									LOGGER.trace(
										"External subtitles ignored because auto loading of external subtitles is disabled: {}",
										present_sub
									);
								} else if (!matchedInternalSubtitles) {
									matchedSub = present_sub;
									if (configuration.isAutoloadExternalSubtitles()) {
										// Subtitle is internal and we will wait to see if an external one is available instead
										LOGGER.trace(
											"Matched internal subtitles track, but will keep looking for an external match: {}",
											matchedSub
										);
										matchedInternalSubtitles = true;
									} else {
										// Subtitle is internal and we will use it
										LOGGER.trace("Matched internal subtitles track: {}", matchedSub);
										break;
									}
								}
							}
						}
					}

					if (matchedSub != null && !matchedInternalSubtitles) {
						break;
					}
				}
			}
		}

		/*
		 * Check for external subtitles that were skipped in the above code block
		 * because they didn't match language preferences, if there wasn't already
		 * a match and the user settings specify it.
		 */
		if (matchedSub == null && configuration.isForceExternalSubtitles()) {
			for (DLNAMediaSubtitle present_sub : media.getSubtitleTracksList()) {
				if (present_sub.isExternal()) {
					matchedSub = present_sub;
					LOGGER.trace("Matched external subtitles track that did not match language preferences: {}", matchedSub);
					break;
				}
			}
		}

		/*
		 * Check for forced subtitles.
		 */
		if (matchedSub == null) {
			if (configuration.isAutoloadExternalSubtitles()) {
				boolean forcedSubsFound = false;
				// Priority to external subtitles //TODO: (Nad) Figure out this mess
				for (DLNAMediaSubtitle sub : media.getSubtitleTracksList()) {
					if (matchedSub != null && matchedSub.getLang() != null && matchedSub.getLang().equals("off")) {
						st = new StringTokenizer(configuration.getForcedSubtitleTags(), ",");

						while (sub.getSubtitlesTrackTitleFromMetadata() != null && st.hasMoreTokens()) {
							String forcedTags = st.nextToken();
							forcedTags = forcedTags.trim();

							if (
								sub.getSubtitlesTrackTitleFromMetadata().toLowerCase().contains(forcedTags) &&
								Iso639.isCodesMatching(sub.getLang(), configuration.getForcedSubtitleLanguage())
							) {
								LOGGER.trace("Forcing preferred subtitles: {}/{}", sub.getLang(), sub.getSubtitlesTrackTitleFromMetadata());
								LOGGER.trace("Forced {} subtitles track: {}", sub.isExternal() ? "external" : "internal", sub);
								matchedSub = sub;
								forcedSubsFound = true;
								break;
							}
						}
						if (forcedSubsFound) {
							break;
						}
					} else {
						LOGGER.trace("Found subtitles track: {}", sub);
						if (sub.isExternal()) {
							LOGGER.trace("Found external file: \"{}\"", sub.getName());
							matchedSub = sub;
							break;
						}
					}
				}
			}
			if (
				matchedSub != null &&
				matchedSub.getLang() != null &&
				matchedSub.getLang().equals("off") //TODO: (Nad) Why?
			) {
				return null;
			}

			if (matchedSub == null) {
				st = new StringTokenizer(UMSUtils.getLangList(renderer), ",");
				while (st.hasMoreTokens()) {
					String lang = st.nextToken();
					lang = lang.trim();
					LOGGER.trace("Looking for a subtitle track with language \"{}\"", lang);
					for (DLNAMediaSubtitle sub : media.getSubtitleTracksList()) {
						if (
							sub.matchCode(lang) &&
							!(
								!configuration.isAutoloadExternalSubtitles() &&
								sub.isExternal()
							)
						) {
							LOGGER.trace("Matched subtitles track: {}", sub);
							return sub;
						}
					}
				}
			}
		}
		return null;
	}

	/**
	 * @see #convertToModX(int, int)
	 */
	@Deprecated
	public int convertToMod4(int number) {
		return convertToModX(number, 4);
	}

	/**
	 * Convert number to be divisible by mod.
	 *
	 * @param number the number to convert
	 * @param mod the number to divide by
	 *
	 * @return the number divisible by mod
	 */
	public static int convertToModX(int number, int mod) {
		if (number % mod != 0) {
			number -= (number % mod);
		}

		return number;
	}

	/**
	 * Returns whether or not the player can handle a given resource.
	 * If the resource is <code>null</code> compatibility cannot be
	 * determined and <code>false</code> will be returned.
	 *
	 * @param resource
	 * The {@link DLNAResource} to be matched.
	 * @return True when the resource can be handled, false otherwise.
	 * @since 1.60.0
	 */
	public abstract boolean isCompatible(DLNAResource resource);

	/**
	 * Returns whether or not another player has the same
	 * name and id as this one.
	 *
	 * @param other
	 * The other player.
	 * @return True if names and ids match, false otherwise.
	 */
	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (other == null) {
			return false;
		}
		if (!(other instanceof Player)) {
			return false;
		}
		Player otherPlayer = (Player) other;
		if (this.name() == null) {
			if (otherPlayer.name() != null) {
				return false;
			}
		} else if (!this.name().equals(otherPlayer.name())) {
			return false;
		}
		if (this.id() == null) {
			if (otherPlayer.id() != null) {
				return false;
			}
		} else if (!this.id().equals(otherPlayer.id())) {
			return false;
		}
		return true;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + (name() == null ? 0 : name().hashCode());
		result = prime * result + (id() == null ? 0 : id().hashCode());
		return result;
	}
}
