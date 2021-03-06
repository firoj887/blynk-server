package cc.blynk.server.application.handlers.main.logic;

import cc.blynk.server.Holder;
import cc.blynk.server.core.BlockingIOProcessor;
import cc.blynk.server.core.model.DashBoard;
import cc.blynk.server.core.model.auth.User;
import cc.blynk.server.core.model.device.Device;
import cc.blynk.server.core.model.widgets.ui.tiles.DeviceTiles;
import cc.blynk.server.core.protocol.model.messages.StringMessage;
import cc.blynk.server.notifications.mail.MailWrapper;
import cc.blynk.utils.StringUtils;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static cc.blynk.server.internal.CommonByteBufUtil.illegalCommandBody;
import static cc.blynk.server.internal.CommonByteBufUtil.notificationError;
import static cc.blynk.server.internal.CommonByteBufUtil.ok;

/**
 * Sends email from application.
 *
 * The Blynk Project.
 * Created by Dmitriy Dumanskiy.
 * Created on 2/1/2015.
 *
 */
public class AppMailLogic {

    private static final Logger log = LogManager.getLogger(AppMailLogic.class);
    private final String tokenMailBody;

    private final BlockingIOProcessor blockingIOProcessor;
    private final MailWrapper mailWrapper;
    private final String templateIdMailBody;

    public AppMailLogic(Holder holder) {
        this.blockingIOProcessor = holder.blockingIOProcessor;
        this.tokenMailBody = holder.textHolder.tokenBody;
        this.mailWrapper = holder.mailWrapper;
        this.templateIdMailBody = holder.textHolder.templateIdMailBody;
    }

    public void messageReceived(ChannelHandlerContext ctx, User user, StringMessage message) {
        var splitBody = message.body.split(StringUtils.BODY_SEPARATOR_STRING);

        //mail type
        switch (splitBody[0]) {
            case "template":
                sendTemplateIdEmail(ctx, user, splitBody, message.id);
                break;
            default:
                var dashId = Integer.parseInt(splitBody[0]);
                var dash = user.profile.getDashByIdOrThrow(dashId);

                //dashId deviceId
                if (splitBody.length == 2) {
                    int deviceId = Integer.parseInt(splitBody[1]);
                    Device device = dash.getDeviceById(deviceId);

                    if (device == null || device.token == null) {
                        log.debug("Wrong device id.");
                        ctx.writeAndFlush(illegalCommandBody(message.id), ctx.voidPromise());
                        return;
                    }

                    makeSingleTokenEmail(ctx, dash, device, user.email, message.id);

                    //dashId theme provisionType color appname
                } else {
                    if (dash.devices.length == 1) {
                        makeSingleTokenEmail(ctx, dash, dash.devices[0], user.email, message.id);
                    } else {
                        sendMultiTokenEmail(ctx, dash, user.email, message.id);
                    }
                }
        }
    }

    private void sendTemplateIdEmail(ChannelHandlerContext ctx, User user, String[] split, int msgId) {
        var dashId = Integer.parseInt(split[1]);
        var dash = user.profile.getDashByIdOrThrow(dashId);

        var widgetId = Long.parseLong(split[2]);
        var deviceTiles = (DeviceTiles) dash.getWidgetById(widgetId);

        var templateId = Long.parseLong(split[3]);
        var template = deviceTiles.getTileTemplateByIdOrThrow(templateId);

        var subj = "Template ID for " + template.name;
        var body = templateIdMailBody
                .replace("{template_name}", template.name)
                .replace("{template_id}", template.templateId);

        log.trace("Sending template id mail for user {}, with id : '{}'.", user.email, templateId);
        mail(ctx.channel(), user.email, subj, body, msgId, true);
    }

    private void makeSingleTokenEmail(ChannelHandlerContext ctx, DashBoard dash, Device device, String to, int msgId) {
        var dashName = dash.name == null ? "New Project" : dash.name;
        var deviceName = device.name == null ? "New Device" : device.name;
        var subj = "Auth Token for " + dashName + " project and device " + deviceName;
        var body = "Auth Token : " + device.token + "\n";

        log.trace("Sending single token mail for user {}, with token : '{}'.", to, device.token);
        mail(ctx.channel(), to, subj, body + tokenMailBody, msgId, false);
    }

    private void sendMultiTokenEmail(ChannelHandlerContext ctx, DashBoard dash, String to, int msgId) {
        var dashName = dash.name == null ? "New Project" : dash.name;
        var subj = "Auth Tokens for " + dashName + " project and " + dash.devices.length + " devices";

        var body = new StringBuilder();
        for (var device : dash.devices) {
            var deviceName = device.name == null ? "New Device" : device.name;
            body.append("Auth Token for device '")
                .append(deviceName)
                .append("' : ")
                .append(device.token)
                .append("\n");
        }

        body.append(tokenMailBody);

        log.trace("Sending multi tokens mail for user {}, with {} tokens.", to, dash.devices.length);
        mail(ctx.channel(), to, subj, body.toString(), msgId, false);
    }

    private void mail(Channel channel, String to, String subj, String body, int msgId, boolean isHtml) {
        blockingIOProcessor.execute(() -> {
            try {
                if (isHtml) {
                    mailWrapper.sendHtml(to, subj, body);
                } else {
                    mailWrapper.sendText(to, subj, body);
                }
                channel.writeAndFlush(ok(msgId), channel.voidPromise());
            } catch (Exception e) {
                log.error("Error sending email auth token to user : {}. Error: {}", to, e.getMessage());
                if (channel.isActive() && channel.isWritable()) {
                    channel.writeAndFlush(notificationError(msgId), channel.voidPromise());
                }
            }
        });
    }
}
