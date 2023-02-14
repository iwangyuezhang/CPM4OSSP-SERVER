package io.jpom.service.node.ssh;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.io.LineHandler;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.CharsetUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.ssh.ChannelType;
import cn.hutool.extra.ssh.JschUtil;
import cn.hutool.extra.ssh.Sftp;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.jcraft.jsch.*;
import io.jpom.common.BaseOperService;
import io.jpom.model.data.NodeModel;
import io.jpom.model.data.SshModel;
import io.jpom.permission.BaseDynamicService;
import io.jpom.plugin.ClassFeature;
import io.jpom.service.node.NodeService;
import io.jpom.system.ConfigBean;
import io.jpom.system.LinuxRuntimeException;
import io.jpom.system.ServerConfigBean;
import io.jpom.system.ServerExtConfigBean;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.io.*;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class SshService extends BaseOperService<SshModel> implements BaseDynamicService {

	@Resource
	private NodeService nodeService;

	public SshService() {
		super(ServerConfigBean.SSH_LIST);
	}

	@Override
	public void addItem(SshModel sshModel) {
		sshModel.setId(IdUtil.fastSimpleUUID());
		super.addItem(sshModel);
	}

	@Override
	public JSONArray listToArray(String dataId) {
		return (JSONArray) JSONArray.toJSON(this.list());
	}

	@Override
	public List<SshModel> list() {
		return (List<SshModel>) filter(super.list(), ClassFeature.SSH);
	}

	public JSONArray listSelect(String nodeId) {
		// 查询ssh
		List<SshModel> sshModels = list();
		List<NodeModel> list = nodeService.list();
		JSONArray sshList = new JSONArray();
		if (sshModels == null) {
			return sshList;
		}
		sshModels.forEach(sshModel -> {
			String sshModelId = sshModel.getId();
			JSONObject jsonObject = new JSONObject();
			jsonObject.put("id", sshModelId);
			jsonObject.put("name", sshModel.getName());
			if (list != null) {
				for (NodeModel nodeModel : list) {
					if (!StrUtil.equals(nodeId, nodeModel.getId()) && StrUtil.equals(sshModelId, nodeModel.getSshId())) {
						jsonObject.put("disabled", true);
						break;
					}
				}
			}
			sshList.add(jsonObject);
		});
		return sshList;
	}

	public static Session getSession(SshModel sshModel) {
		Session session;
		if (sshModel.getConnectType() == SshModel.ConnectType.PASS) {
			session = JschUtil.openSession(sshModel.getHost(), sshModel.getPort(), sshModel.getUser(), sshModel.getPassword());

		} else if (sshModel.getConnectType() == SshModel.ConnectType.PUBKEY) {
			File tempPath = ConfigBean.getInstance().getTempPath();
			String sshFile = StrUtil.emptyToDefault(sshModel.getId(), IdUtil.fastSimpleUUID());
			File ssh = FileUtil.file(tempPath, "ssh", sshFile);
			FileUtil.writeString(sshModel.getPrivateKey(), ssh, CharsetUtil.UTF_8);
			byte[] pas = null;
			if (StrUtil.isNotEmpty(sshModel.getPassword())) {
				pas = sshModel.getPassword().getBytes();
			}
			session = JschUtil.openSession(sshModel.getHost(), sshModel.getPort(), sshModel.getUser(), FileUtil.getAbsolutePath(ssh), pas);
		} else {
			throw new IllegalArgumentException("不支持的模式");
		}
		try {
			session.setServerAliveInterval((int) TimeUnit.SECONDS.toMillis(5));
			session.setServerAliveCountMax(5);
		} catch (JSchException ignored) {
		}
		return session;

	}

	/**
	 * 检查是否存在正在运行的进程
	 *
	 * @param sshModel ssh
	 * @param tag      标识
	 * @return true 存在运行中的
	 * @throws IOException   IO
	 * @throws JSchException jsch
	 */
	public boolean checkSshRun(SshModel sshModel, String tag) throws IOException, JSchException {
		String ps = StrUtil.format("ps -ef | grep -v 'grep' | egrep {}", tag);
		Session session = null;
		ChannelExec channel = null;
		try {
			session = getSession(sshModel);
			channel = (ChannelExec) JschUtil.createChannel(session, ChannelType.EXEC);
			channel.setCommand(ps);
			InputStream inputStream = channel.getInputStream();
			InputStream errStream = channel.getErrStream();
			channel.connect();
			Charset charset = sshModel.getCharsetT();
			// 运行中
			AtomicBoolean run = new AtomicBoolean(false);
			IoUtil.readLines(inputStream, charset, (LineHandler) s -> {
				run.set(true);
			});
			if (run.get()) {
				return true;
			}
			run.set(false);
			AtomicReference<String> error = new AtomicReference<>();
			IoUtil.readLines(errStream, charset, (LineHandler) s -> {
				run.set(true);
				error.set(s);
			});
			if (run.get()) {
				throw new LinuxRuntimeException("检查异常:" + error.get());
			}
		} finally {
			JschUtil.close(channel);
			JschUtil.close(session);
		}
		return false;
	}

}
