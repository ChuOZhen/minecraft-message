package com.msgboard.gametest.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.msgboard.gametest.CapturedChat;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;

/**
 * 在客户端处理服务端系统聊天之前，把原始组件抄一份给测试用。
 */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {
	@Inject(method = "handleSystemChat", at = @At("HEAD"))
	private void msgboard$captureSystemChat(ClientboundSystemChatPacket packet, CallbackInfo ci) {
		if (packet != null && !packet.overlay()) {
			CapturedChat.record(packet.content());
		}
	}
}
