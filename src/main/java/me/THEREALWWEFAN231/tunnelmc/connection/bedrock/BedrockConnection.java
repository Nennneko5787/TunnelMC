package me.THEREALWWEFAN231.tunnelmc.connection.bedrock;

import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.nukkitx.api.event.Listener;
import com.nukkitx.network.util.DisconnectReason;
import com.nukkitx.protocol.bedrock.BedrockClient;
import com.nukkitx.protocol.bedrock.BedrockPacket;
import com.nukkitx.protocol.bedrock.BedrockPacketCodec;
import com.nukkitx.protocol.bedrock.BedrockSession;
import com.nukkitx.protocol.bedrock.data.AuthoritativeMovementMode;
import com.nukkitx.protocol.bedrock.data.GameType;
import com.nukkitx.protocol.bedrock.packet.LoginPacket;
import com.nukkitx.protocol.bedrock.v545.Bedrock_v545;
import io.netty.util.AsciiString;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import me.THEREALWWEFAN231.tunnelmc.TunnelMC;
import me.THEREALWWEFAN231.tunnelmc.connection.PacketTranslatorManager;
import me.THEREALWWEFAN231.tunnelmc.connection.bedrock.auth.OfflineModeLoginChainSupplier;
import me.THEREALWWEFAN231.tunnelmc.connection.bedrock.auth.OnlineModeLoginChainSupplier;
import me.THEREALWWEFAN231.tunnelmc.connection.bedrock.auth.data.AuthData;
import me.THEREALWWEFAN231.tunnelmc.connection.bedrock.auth.data.ChainData;
import me.THEREALWWEFAN231.tunnelmc.connection.bedrock.auth.data.ClientData;
import me.THEREALWWEFAN231.tunnelmc.connection.bedrock.auth.data.DeviceOS;
import me.THEREALWWEFAN231.tunnelmc.connection.bedrock.network.BedrockPacketTranslatorManager;
import me.THEREALWWEFAN231.tunnelmc.connection.bedrock.network.ClientBatchHandler;
import me.THEREALWWEFAN231.tunnelmc.connection.bedrock.network.caches.BlockEntityDataCache;
import me.THEREALWWEFAN231.tunnelmc.connection.bedrock.network.caches.container.BedrockContainers;
import me.THEREALWWEFAN231.tunnelmc.connection.java.FakeJavaConnection;
import me.THEREALWWEFAN231.tunnelmc.events.PlayerInitializedEvent;
import me.THEREALWWEFAN231.tunnelmc.events.SessionInitializedEvent;
import me.THEREALWWEFAN231.tunnelmc.gui.BedrockConnectingScreen;
import me.THEREALWWEFAN231.tunnelmc.utils.FileUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.Packet;
import net.minecraft.text.Text;

import javax.crypto.SecretKey;
import javax.imageio.ImageIO;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static me.THEREALWWEFAN231.tunnelmc.TunnelMC.JSON_MAPPER;

@Log4j2
public class BedrockConnection {
	public static final BedrockPacketCodec CODEC = Bedrock_v545.V545_CODEC;

	private final InetSocketAddress targetAddress;
	private final PacketTranslatorManager<BedrockPacket> packetTranslatorManager;
	final BedrockClient bedrockClient;
	private FakeJavaConnection javaConnection;

	@Getter
	private ChainData chainData;
	@Getter
	private AuthData authData;
	private BedrockConnectingScreen connectScreen;

	@Getter
	private BedrockContainers wrappedContainers;
	@Getter
	private BlockEntityDataCache blockEntityDataCache;

	public int entityRuntimeId;
	public AuthoritativeMovementMode movementMode = AuthoritativeMovementMode.CLIENT;
	public GameType defaultGameMode;
	public AtomicBoolean startedSprinting = new AtomicBoolean();
	public AtomicBoolean startedSneaking = new AtomicBoolean();
	public AtomicBoolean stoppedSprinting = new AtomicBoolean();
	public AtomicBoolean stoppedSneaking = new AtomicBoolean();
	public AtomicBoolean jumping = new AtomicBoolean();

	BedrockConnection(InetSocketAddress bindAddress, InetSocketAddress targetAddress) {
		this.bedrockClient = new BedrockClient(bindAddress);
		this.bedrockClient.bind().join();
		this.packetTranslatorManager = new BedrockPacketTranslatorManager();
		this.targetAddress = targetAddress;

		TunnelMC.getInstance().getEventManager().registerListeners(this, this);
	}

	public void connect(boolean onlineMode) {
		this.connectScreen = new BedrockConnectingScreen(MinecraftClient.getInstance().currentScreen, MinecraftClient.getInstance(), BedrockConnectionAccessor::closeConnection);
		TunnelMC.mc.setScreen(this.connectScreen);

		LoginChainSupplier supplier;
		if (onlineMode) {
			supplier = new OnlineModeLoginChainSupplier(s -> this.connectScreen.setStatus(Text.of(s)));
		} else {
			supplier = new OfflineModeLoginChainSupplier(TunnelMC.mc.getSession().getUsername());
		}

		supplier.get().whenComplete((chainData, throwable) -> {
			if(throwable != null) {
				BedrockConnectionAccessor.closeConnection(throwable);
				return;
			}

			this.chainData = chainData;
			this.authData = this.chainData.decodeAuthData();
			this.connectScreen.setStatus(Text.translatable("connect.connecting"));

			this.bedrockClient.connect(this.targetAddress).whenComplete((session, throwable1) -> {
				if (throwable1 != null) {
					BedrockConnectionAccessor.closeConnection(throwable1);
					return;
				}

				this.connectScreen.setStatus(Text.of("Logging in..."));
				TunnelMC.getInstance().getEventManager().fire(new SessionInitializedEvent(session));
			});
		});
	}

	public void sendPacketImmediately(BedrockPacket packet) {
		BedrockSession session = this.bedrockClient.getSession();

		if (session != null) {
			session.sendPacketImmediately(packet);
			if (session.isLogging()) {
				log.info("Outbound {}: {}", session.getAddress().toString(), packet.getClass().getCanonicalName());
			}
		}
	}

	public void handleJavaPacket(Packet<?> packet) {
		this.javaConnection.translatePacket(packet);
	}

	public void sendPacket(BedrockPacket packet) {
		BedrockSession session = this.bedrockClient.getSession();

		if (session != null) {
			session.sendPacket(packet);
			if (session.isLogging()) {
				log.info("Outbound {}: {}", session.getAddress().toString(), packet.getClass().getCanonicalName());
			}
		}
	}

	public void setHardcodedBlockingId(int id) {
		System.out.println(this.bedrockClient.getSession().getHardcodedBlockingId().get());
		if(!this.bedrockClient.getSession().getHardcodedBlockingId().compareAndSet(-1, id)) {
			throw new IllegalStateException("Blocking id is already set");
		}
	}

	public void enableEncryption(SecretKey key) {
		if(this.bedrockClient.getSession().isEncrypted()) {
			throw new IllegalStateException("Connection is already encrypted");
		}
		this.bedrockClient.getSession().enableEncryption(key);
	}

	@Listener
	public void onEvent(SessionInitializedEvent event) {
		BedrockSession bedrockSession = event.getSession();
		FakeJavaConnection javaConnection = new FakeJavaConnection(this);

		bedrockSession.setPacketCodec(CODEC);
		bedrockSession.addDisconnectHandler(reason -> MinecraftClient.getInstance().execute(() -> {
			// We disconnected ourselves.
			if (reason == DisconnectReason.DISCONNECTED) {
				return;
			}

			BedrockConnectionAccessor.closeConnection("You were disconnected from the target server because: " + reason.toString());
		}));

		bedrockSession.setBatchHandler(new ClientBatchHandler(this, javaConnection, this.packetTranslatorManager));
		bedrockSession.setLogging(false);

		try {
			LoginPacket loginPacket = new LoginPacket();

			UUID uuid = Objects.requireNonNull(TunnelMC.mc.getSession().getUuidOrNull());
			ClientData clientData = new ClientData();
			clientData.setArmSize(ClientData.ArmSizeType.fromUUID(uuid));
			clientData.setClientRandomId(uuid.getLeastSignificantBits());
			clientData.setCurrentInputMode(1);
			clientData.setDefaultInputMode(1);
			clientData.setDeviceOS(DeviceOS.MICROSOFT_WINDOWS_10);
			clientData.setGameVersion(BedrockConnection.CODEC.getMinecraftVersion());
			clientData.setSkinGeometryVersion(Base64.getEncoder().withoutPadding().encodeToString(BedrockConnection.CODEC.getMinecraftVersion().getBytes(StandardCharsets.UTF_8)));
			clientData.setLanguageCode(TunnelMC.mc.getLanguageManager().getLanguage().getCode());
			clientData.setSelfSignedId(uuid);
			clientData.setServerAddress(this.targetAddress.getHostName() + ":" + this.targetAddress.getPort());
			clientData.setThirdPartyName(authData.displayName());
			clientData.setSkinGeometryData(Base64.getEncoder().withoutPadding().encodeToString(
					JSON_MAPPER.writeValueAsBytes(FileUtils.getJsonFromResource("tunnel/geometry_data.json"))));
			clientData.setSkinResourcePatch(clientData.getArmSize().getEncodedGeometryData());
			clientData.setTrustedSkin(true);

			Map<MinecraftProfileTexture.Type, MinecraftProfileTexture> textures = TunnelMC.mc.getSessionService().getTextures(TunnelMC.mc.getSession().getProfile(), false);
			try {
				MinecraftProfileTexture skinTexture = Optional.ofNullable(textures.get(MinecraftProfileTexture.Type.SKIN))
						.orElse(new MinecraftProfileTexture(clientData.getArmSize().getDefaultSkinUrl(), Collections.emptyMap()));
				clientData.setSkin(ImageIO.read(new URL(skinTexture.getUrl())));
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
			Optional.ofNullable(textures.get(MinecraftProfileTexture.Type.CAPE))
					.ifPresent(capeTexture -> {
						try {
							clientData.setCape(ImageIO.read(new URL(capeTexture.getUrl())));
						} catch (IOException e) {
							throw new RuntimeException(e);
						}
					});

			loginPacket.setProtocolVersion(bedrockSession.getPacketCodec().getProtocolVersion());
			loginPacket.setChainData(new AsciiString(chainData.rawData().getBytes()));
			loginPacket.setSkinData(new AsciiString(clientData.getAsJWT(chainData)));
			this.sendPacketImmediately(loginPacket);

			this.connectScreen.setStatus(Text.of("Loading resources..."));
			this.javaConnection = javaConnection;
		} catch (Exception e) {
			BedrockConnectionAccessor.closeConnection(e);
		}
	}

	@Listener
	public void onEvent(PlayerInitializedEvent event) {
		this.wrappedContainers = new BedrockContainers();
		this.blockEntityDataCache = new BlockEntityDataCache();
	}
}