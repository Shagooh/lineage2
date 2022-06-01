/*
 * Copyright © 2004-2022 L2J Server
 * 
 * This file is part of L2J Server.
 * 
 * L2J Server is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * L2J Server is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.l2jserver.gameserver.instancemanager;

import static com.l2jserver.gameserver.config.Configuration.general;
import static com.l2jserver.gameserver.config.Configuration.server;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import com.l2jserver.commons.database.ConnectionFactory;
import com.l2jserver.commons.util.Rnd;
import com.l2jserver.gameserver.datatables.SpawnTable;
import com.l2jserver.gameserver.model.DimensionalRiftRoom;
import com.l2jserver.gameserver.model.L2Spawn;
import com.l2jserver.gameserver.model.actor.L2Npc;
import com.l2jserver.gameserver.model.actor.instance.L2PcInstance;
import com.l2jserver.gameserver.model.entity.DimensionalRift;
import com.l2jserver.gameserver.model.items.instance.L2ItemInstance;
import com.l2jserver.gameserver.network.serverpackets.NpcHtmlMessage;
import com.l2jserver.gameserver.util.Util;

/**
 * Dimensional Rift manager.
 * @author kombat
 */
public final class DimensionalRiftManager {
	
	private static final Logger LOG = LoggerFactory.getLogger(DimensionalRiftManager.class);
	
	private final Map<Byte, Map<Byte, DimensionalRiftRoom>> _rooms = new HashMap<>(7);
	
	private static final int DIMENSIONAL_FRAGMENT_ITEM_ID = 7079;
	
	public static DimensionalRiftManager getInstance() {
		return SingletonHolder._instance;
	}
	
	protected DimensionalRiftManager() {
		loadRooms();
		loadSpawns();
	}
	
	public DimensionalRiftRoom getRoom(byte type, byte room) {
		return _rooms.get(type) == null ? null : _rooms.get(type).get(room);
	}
	
	private void loadRooms() {
		try (var con = ConnectionFactory.getInstance().getConnection();
			var s = con.createStatement();
			var rs = s.executeQuery("SELECT * FROM dimensional_rift")) {
			while (rs.next()) {
				// 0 waiting room, 1 recruit, 2 soldier, 3 officer, 4 captain , 5 commander, 6 hero
				byte type = rs.getByte("type");
				byte room_id = rs.getByte("room_id");
				
				// coords related
				int xMin = rs.getInt("xMin");
				int xMax = rs.getInt("xMax");
				int yMin = rs.getInt("yMin");
				int yMax = rs.getInt("yMax");
				int z1 = rs.getInt("zMin");
				int z2 = rs.getInt("zMax");
				int xT = rs.getInt("xT");
				int yT = rs.getInt("yT");
				int zT = rs.getInt("zT");
				boolean isBossRoom = rs.getByte("boss") > 0;
				
				if (!_rooms.containsKey(type)) {
					_rooms.put(type, new HashMap<>(9));
				}
				
				_rooms.get(type).put(room_id, new DimensionalRiftRoom(type, room_id, xMin, xMax, yMin, yMax, z1, z2, xT, yT, zT, isBossRoom));
			}
		} catch (Exception ex) {
			LOG.warn("Can not load Dimension Rift zones!", ex);
		}
		
		int typeSize = _rooms.keySet().size();
		int roomSize = 0;
		
		for (byte b : _rooms.keySet()) {
			roomSize += _rooms.get(b).keySet().size();
		}
		
		LOG.info("Loaded {} room types with {} rooms.", typeSize, roomSize);
	}
	
	public void loadSpawns() {
		int countGood = 0, countBad = 0;
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setValidating(false);
			factory.setIgnoringComments(true);
			
			File file = new File(server().getDatapackRoot(), "data/dimensionalRift.xml");
			if (!file.exists()) {
				LOG.warn("Could not find file {}!", file.getAbsoluteFile());
				return;
			}
			
			Document doc = factory.newDocumentBuilder().parse(file);
			NamedNodeMap attrs;
			byte type, roomId;
			int mobId, x, y, z, delay, count;
			
			for (Node rift = doc.getFirstChild(); rift != null; rift = rift.getNextSibling()) {
				if ("rift".equalsIgnoreCase(rift.getNodeName())) {
					for (Node area = rift.getFirstChild(); area != null; area = area.getNextSibling()) {
						if ("area".equalsIgnoreCase(area.getNodeName())) {
							attrs = area.getAttributes();
							type = Byte.parseByte(attrs.getNamedItem("type").getNodeValue());
							
							for (Node room = area.getFirstChild(); room != null; room = room.getNextSibling()) {
								if ("room".equalsIgnoreCase(room.getNodeName())) {
									attrs = room.getAttributes();
									roomId = Byte.parseByte(attrs.getNamedItem("id").getNodeValue());
									
									for (Node spawn = room.getFirstChild(); spawn != null; spawn = spawn.getNextSibling()) {
										if ("spawn".equalsIgnoreCase(spawn.getNodeName())) {
											attrs = spawn.getAttributes();
											mobId = Integer.parseInt(attrs.getNamedItem("mobId").getNodeValue());
											delay = Integer.parseInt(attrs.getNamedItem("delay").getNodeValue());
											count = Integer.parseInt(attrs.getNamedItem("count").getNodeValue());
											
											if (!_rooms.containsKey(type)) {
												LOG.warn("Type {} not found!", type);
											} else if (!_rooms.get(type).containsKey(roomId)) {
												LOG.warn("Room {} in type {} not found!", roomId, type);
											}
											
											for (int i = 0; i < count; i++) {
												final DimensionalRiftRoom riftRoom = _rooms.get(type).get(roomId);
												x = riftRoom.getRandomX();
												y = riftRoom.getRandomY();
												z = riftRoom.getTeleportCoordinates().getZ();
												
												if (_rooms.containsKey(type) && _rooms.get(type).containsKey(roomId)) {
													final L2Spawn spawnDat = new L2Spawn(mobId);
													spawnDat.setAmount(1);
													spawnDat.setLocation(x, y, z, -1);
													spawnDat.setRespawnDelay(delay);
													SpawnTable.getInstance().addNewSpawn(spawnDat, false);
													_rooms.get(type).get(roomId).getSpawns().add(spawnDat);
													countGood++;
												} else {
													countBad++;
												}
											}
										}
									}
								}
							}
						}
					}
				}
			}
		} catch (Exception ex) {
			LOG.warn("There was an error on loading Dimensional Rift spawns!", ex);
		}
		
		LOG.info("Loaded {} Dimensional Rift spawns.", countGood);
		
		if (countBad > 0) {
			LOG.warn("There has been {} errors in DImensinal Rift spawns!", countBad);
		}
	}
	
	public void reload() {
		for (byte b : _rooms.keySet()) {
			for (byte i : _rooms.get(b).keySet()) {
				_rooms.get(b).get(i).getSpawns().clear();
			}
			_rooms.get(b).clear();
		}
		_rooms.clear();
		loadRooms();
		loadSpawns();
	}
	
	public boolean checkIfInRiftZone(int x, int y, int z, boolean ignorePeaceZone) {
		if (ignorePeaceZone) {
			return _rooms.get((byte) 0).get((byte) 1).checkIfInZone(x, y, z);
		}
		
		return _rooms.get((byte) 0).get((byte) 1).checkIfInZone(x, y, z) && !_rooms.get((byte) 0).get((byte) 0).checkIfInZone(x, y, z);
	}
	
	public boolean checkIfInPeaceZone(int x, int y, int z) {
		return _rooms.get((byte) 0).get((byte) 0).checkIfInZone(x, y, z);
	}
	
	public void teleportToWaitingRoom(L2PcInstance player) {
		player.teleToLocation(getRoom((byte) 0, (byte) 0).getTeleportCoordinates());
	}
	
	public synchronized void start(L2PcInstance player, byte type, L2Npc npc) {
		boolean canPass = true;
		if (!player.isInParty()) {
			showHtmlFile(player, "data/html/seven_signs/rift/NoParty.htm", npc);
			return;
		}
		
		if (player.getParty().getLeaderObjectId() != player.getObjectId()) {
			showHtmlFile(player, "data/html/seven_signs/rift/NotPartyLeader.htm", npc);
			return;
		}
		
		if (player.getParty().isInDimensionalRift()) {
			handleCheat(player, npc);
			return;
		}
		
		if (player.getParty().getMemberCount() < general().getRiftMinPartySize()) {
			final NpcHtmlMessage html = new NpcHtmlMessage(npc.getObjectId());
			html.setFile(player.getHtmlPrefix(), "data/html/seven_signs/rift/SmallParty.htm");
			html.replace("%npc_name%", npc.getName());
			html.replace("%count%", Integer.toString(general().getRiftMinPartySize()));
			player.sendPacket(html);
			return;
		}
		
		// max parties inside is rooms count - 1
		if (!isAllowedEnter(type)) {
			player.sendMessage("Rift is full. Try later.");
			return;
		}
		
		for (L2PcInstance p : player.getParty().getMembers()) {
			if (!checkIfInPeaceZone(p.getX(), p.getY(), p.getZ())) {
				canPass = false;
				break;
			}
		}
		
		if (!canPass) {
			showHtmlFile(player, "data/html/seven_signs/rift/NotInWaitingRoom.htm", npc);
			return;
		}
		
		L2ItemInstance i;
		int count = getNeededItems(type);
		for (L2PcInstance p : player.getParty().getMembers()) {
			i = p.getInventory().getItemByItemId(DIMENSIONAL_FRAGMENT_ITEM_ID);
			
			if (i == null) {
				canPass = false;
				break;
			}
			
			if (i.getCount() > 0) {
				if (i.getCount() < getNeededItems(type)) {
					canPass = false;
					break;
				}
			} else {
				canPass = false;
				break;
			}
		}
		
		if (!canPass) {
			final NpcHtmlMessage html = new NpcHtmlMessage(npc.getObjectId());
			html.setFile(player.getHtmlPrefix(), "data/html/seven_signs/rift/NoFragments.htm");
			html.replace("%npc_name%", npc.getName());
			html.replace("%count%", Integer.toString(count));
			player.sendPacket(html);
			return;
		}
		
		for (L2PcInstance p : player.getParty().getMembers()) {
			i = p.getInventory().getItemByItemId(DIMENSIONAL_FRAGMENT_ITEM_ID);
			if (!p.destroyItem("RiftEntrance", i, count, null, false)) {
				final NpcHtmlMessage html = new NpcHtmlMessage(npc.getObjectId());
				html.setFile(player.getHtmlPrefix(), "data/html/seven_signs/rift/NoFragments.htm");
				html.replace("%npc_name%", npc.getName());
				html.replace("%count%", Integer.toString(count));
				player.sendPacket(html);
				return;
			}
		}
		
		byte room;
		List<Byte> emptyRooms;
		do {
			emptyRooms = getFreeRooms(type);
			room = emptyRooms.get(Rnd.get(1, emptyRooms.size()) - 1);
		}
		// find empty room
		while (_rooms.get(type).get(room).isPartyInside());
		new DimensionalRift(player.getParty(), type, room);
	}
	
	public void killRift(DimensionalRift d) {
		if (d.getTeleportTimerTask() != null) {
			d.getTeleportTimerTask().cancel();
		}
		d.setTeleportTimerTask(null);
		
		if (d.getTeleportTimer() != null) {
			d.getTeleportTimer().cancel();
		}
		d.setTeleportTimer(null);
		
		if (d.getSpawnTimerTask() != null) {
			d.getSpawnTimerTask().cancel();
		}
		d.setSpawnTimerTask(null);
		
		if (d.getSpawnTimer() != null) {
			d.getSpawnTimer().cancel();
		}
		d.setSpawnTimer(null);
	}
	
	private int getNeededItems(byte type) {
		return switch (type) {
			case 1 -> general().getRecruitCost();
			case 2 -> general().getSoldierCost();
			case 3 -> general().getOfficerCost();
			case 4 -> general().getCaptainCost();
			case 5 -> general().getCommanderCost();
			case 6 -> general().getHeroCost();
			default -> throw new IndexOutOfBoundsException();
		};
	}
	
	public void showHtmlFile(L2PcInstance player, String file, L2Npc npc) {
		final NpcHtmlMessage html = new NpcHtmlMessage(npc.getObjectId());
		html.setFile(player.getHtmlPrefix(), file);
		html.replace("%npc_name%", npc.getName());
		player.sendPacket(html);
	}
	
	public void handleCheat(L2PcInstance player, L2Npc npc) {
		showHtmlFile(player, "data/html/seven_signs/rift/Cheater.htm", npc);
		if (!player.isGM()) {
			LOG.warn("Player {} was cheating in dimension rift area!", player);
			Util.handleIllegalPlayerAction(player, "Warning!! Character " + player.getName() + " tried to cheat in dimensional rift.");
		}
	}
	
	public boolean isAllowedEnter(byte type) {
		int count = 0;
		for (DimensionalRiftRoom room : _rooms.get(type).values()) {
			if (room.isPartyInside()) {
				count++;
			}
		}
		return (count < (_rooms.get(type).size() - 1));
	}
	
	public List<Byte> getFreeRooms(byte type) {
		List<Byte> list = new ArrayList<>();
		for (DimensionalRiftRoom room : _rooms.get(type).values()) {
			if (!room.isPartyInside()) {
				list.add(room.getRoom());
			}
		}
		return list;
	}
	
	private static class SingletonHolder {
		protected static final DimensionalRiftManager _instance = new DimensionalRiftManager();
	}
}
