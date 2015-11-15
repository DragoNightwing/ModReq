package nu.nerd.modreq;

import com.avaje.ebean.SqlRow;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

import javax.persistence.PersistenceException;
import nu.nerd.modreq.database.Note;
import nu.nerd.modreq.database.NoteTable;

import nu.nerd.modreq.database.Request;
import nu.nerd.modreq.database.Request.RequestStatus;
import nu.nerd.modreq.database.RequestTable;
import org.bukkit.Bukkit;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permissible;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.gestern.bukkitmigration.UUIDFetcher;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

public class ModReq extends JavaPlugin {
    ModReqListener listener = new ModReqListener(this);
    Configuration config = new Configuration(this);
    Map<String, String> environment = new HashMap<String, String>();

    RequestTable reqTable;
    NoteTable noteTable;

    @Override
    public void onEnable() {
        setupDatabase();
        File configFile = new File(this.getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            getConfig().options().copyDefaults(true);
            saveConfig();
        }

        config.load();

        reqTable = new RequestTable(this);
        noteTable = new NoteTable(this);
        getServer().getPluginManager().registerEvents(listener, this);
    }

    @Override
    public void onDisable() {
        // tear down
    }

    public boolean setupDatabase() {
        try {
            getDatabase().find(Request.class).findRowCount();
            getDatabase().find(Note.class).findRowCount();
        } catch (PersistenceException ex) {
            getLogger().log(Level.INFO, "First run, initializing database.");
            installDDL();
            return true;
        }

        return false;
    }

    public void resetDatabase() {
        getLogger().log(Level.INFO, "Resetting database");

        getLogger().log(Level.INFO, "Backup up existing data into memory");
		List<SqlRow> rowRequests = getDatabase().createSqlQuery("SELECT id, player_name, assigned_mod, request, request_time, status, request_location, close_message, close_time, close_seen_by_user, flag_for_admin FROM modreq_requests").findList();
		List<SqlRow> rowNotes = getDatabase().createSqlQuery("SELECT id, player, request_id, note_body FROM modreq_notes").findList();

        List<Request> reqs = new ArrayList<Request>();
		List<Note> notes = new ArrayList<Note>();
		Set<String> unknownNames = new HashSet<String>();

        getLogger().log(Level.INFO, "Executing remove ddl");
        removeDDL();

        if (setupDatabase()) {
            getLogger().log(Level.INFO, "Schema created, converting " + rowRequests.size() + " requests and " + rowNotes.size() + " notes");
			for (SqlRow row : rowRequests) {
                Request req = new Request();
                req.setId(row.getInteger("id"));
				if (row.containsKey("player_uuid"))
					req.setPlayerUUID(row.getUUID("player_uuid"));
                req.setPlayerName(row.getString("player_name"));
                req.setRequest(row.getString("request"));
                req.setRequestTime(row.getInteger("request_time"));
                req.setRequestLocation(row.getString("request_location"));
                req.setStatus(RequestStatus.values()[row.getInteger("status")]);
                if (req.getStatus() == RequestStatus.CLAIMED) {
					if (row.containsKey("assigned_mod_uuid"))
						req.setAssignedModUUID(row.getUUID("assigned_mod_uuid"));
                }
                req.setAssignedMod(row.getString("assigned_mod"));
                req.setFlagForAdmin(row.getBoolean("flag_for_admin"));

				if (req.getPlayerUUID() == null && req.getPlayerName() != null) {
					unknownNames.add(req.getPlayerName());
				}
				if (req.getAssignedModUUID() == null && req.getAssignedMod() != null) {
					unknownNames.add(req.getAssignedMod());
				}

				reqs.add(req);
			}

			for (SqlRow row : rowNotes) {
				Note note = new Note();
				note.setId(row.getInteger("id"));
				note.setPlayer(row.getString("player"));
				note.setRequestId(row.getInteger("request_id"));
				note.setNoteBody(row.getString("note_body"));

				if (note.getPlayerUUID() == null && note.getPlayer() != null) {
					unknownNames.add(note.getPlayer());
				}

				notes.add(note);
			}

			if (unknownNames.size() > 0) {
                getLogger().log(Level.INFO, "Fetching " + unknownNames.size() + " UUIDs");
				try {
					List<String> names = new ArrayList<String>(unknownNames);
					UUIDFetcher fetcher = new UUIDFetcher(names);
					Map<String, UUID> responses = fetcher.call();

					List<String> namesChanged = new ArrayList<String>();
					for (String name : names) {
						if (!responses.containsKey(name)) {
							namesChanged.add(name);
						}
					}
					getLogger().log(Level.INFO, "Failed to lookup " + namesChanged.size() + " uuids, querying for history");
					final JSONParser jsonParser = new JSONParser();
					int i = 0;
					for (String name : namesChanged) {
						try {
							URL url = new URL("https://api.mojang.com/users/profiles/minecraft/" + name + "?at=1422774069");
							HttpURLConnection connection = (HttpURLConnection) url.openConnection();
							connection.setRequestProperty("Content-Type", "application/json");
							connection.setUseCaches(false);
							connection.setDoInput(true);
							connection.setDoOutput(true);

							JSONObject profile = (JSONObject) jsonParser.parse(new InputStreamReader(connection.getInputStream()));
							//String nameNew = (String) profile.get("name");
							String uuidStringNoDash = (String) profile.get("id");
							String uuidString =  uuidStringNoDash.replaceFirst( "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)", "$1-$2-$3-$4-$5" );
							UUID uuid = UUID.fromString(uuidString);
							responses.put(name, uuid);
                            //getLogger().info("New Name = " + nameNew + " UUID = " + uuid);
						}
						catch (Exception e) {
							getLogger().log(Level.INFO, "Failed to fetch historical uuid for " + name);
						}

						i++;
						if (i > 600)
							Thread.sleep(100L);
					}

					for (Map.Entry<String, UUID> response : responses.entrySet()) {
						for (Request req : reqs) {
							if (req.getPlayerName() != null && req.getPlayerName().equalsIgnoreCase(response.getKey())) {
								req.setPlayerUUID(response.getValue());
							}
							if (req.getAssignedMod() != null && req.getAssignedMod().equalsIgnoreCase(response.getKey())) {
								req.setAssignedModUUID(response.getValue());
							}
						}

						for (Note note : notes) {
							if (note.getPlayer() != null && note.getPlayer().equalsIgnoreCase(response.getKey())) {
								note.setPlayerUUID(response.getValue());
							}
						}
					}
				}
				catch (Exception e) {
					getLogger().log(Level.SEVERE, "Failed to fetch uuids", e);
				}
			}

			getLogger().log(Level.INFO, "Saving " + reqs.size() + " reqs");
			int i = 0;
			for (Request req : reqs) {
				try {
					i++;

					reqTable.save(req);

					if (i % 1000 == 0) {
						getLogger().info("Saved " + i + " of " + reqs.size() + " reqs");
						Thread.sleep(1000L);
					}
				}
				catch (Exception e)
				{
					getLogger().log(Level.SEVERE, "Failed to save ModReq id=" + req.getId() + " player=" + req.getPlayerName());
					getLogger().log(Level.SEVERE, e.getMessage());
				}
			}
			getLogger().info("Saved " + i + " of " + reqs.size() + " reqs");


			getLogger().log(Level.INFO, "Saving " + notes.size() + " notes");
			i = 0;
			for (Note note : notes) {
				try {
					i++;

					noteTable.save(note);

					if (i % 1000 == 0) {
						getLogger().info("Saved " + i + " of " + notes.size() + " notes");
						Thread.sleep(1000L);
					}
				}
				catch (Exception e)
				{
					getLogger().log(Level.SEVERE, "Failed to save note id=" + note.getId() + " player=" + note.getPlayer());
					getLogger().log(Level.SEVERE, e.getMessage());
				}
			}
			getLogger().info("Saved " + i + " of " + notes.size() + " notes");
        }
        getLogger().log(Level.INFO, "Done");
    }

    @Override
    public ArrayList<Class<?>> getDatabaseClasses() {
        ArrayList<Class<?>> list = new ArrayList<Class<?>>();
        list.add(Request.class);
        list.add(Note.class);
        return list;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String name, String[] args) {
        String senderName = ChatColor.stripColor(sender.getName());
		UUID senderUUID = null;
		Player player = null;
		if (sender instanceof Player) {
			player = (Player)sender;
			senderUUID = player.getUniqueId();
		}
        environment.clear();
        if (sender instanceof ConsoleCommandSender) {
            senderName = "Console";
        }
        if (command.getName().equalsIgnoreCase("modreq")) {
            if (args.length == 0) {
                return false;
            }

            StringBuilder request = new StringBuilder(args[0]);
            for (int i = 1; i < args.length; i++) {
                request.append(" ").append(args[i]);
            }

            if (sender instanceof Player) {
                if (reqTable.getNumRequestFromUser(player.getUniqueId()) < config.MAX_REQUESTS) {
                    Request req = new Request();
					req.setPlayerUUID(player.getUniqueId());
                    req.setPlayerName(senderName);
                    String r = ChatColor.translateAlternateColorCodes('&', request.toString());
                    r = ChatColor.stripColor(r);
                    req.setRequest(r);
                    req.setRequestTime(System.currentTimeMillis());
                    String location = String.format("%s,%f,%f,%f,%f,%f", player.getWorld().getName(), player.getLocation().getX(), player.getLocation().getY(), player.getLocation().getZ(), player.getLocation().getYaw(), player.getLocation().getPitch());
                    req.setRequestLocation(location);
                    req.setStatus(RequestStatus.OPEN);

                    reqTable.save(req);
                    environment.put("request_id", String.valueOf(req.getId()));
                    messageMods(config.MOD__NEW_REQUEST);
                    sendMessage(sender, config.GENERAL__REQUEST_FILED);
                } else {
                    environment.put("max_requests", config.MAX_REQUESTS.toString());
                    sendMessage(sender, config.GENERAL__MAX_REQUESTS);
                }
            }
        }
        else if (command.getName().equalsIgnoreCase("check")) {
            check(sender, senderUUID, args);
        }
        else if (command.getName().equalsIgnoreCase("tp-id")) {
            if (args.length == 0) {
                return false;
            }
            tpId(sender, player, args);
        }
        else if (command.getName().equalsIgnoreCase("tpinfo")) {
            if (args.length == 0) {
                return false;
            }
            tpId(sender, player, args);
            check(sender, senderUUID, args);
        }
        else if (command.getName().equalsIgnoreCase("tpc")) {
            if (args.length == 0) {
                return false;
            }
            if (claim(sender, senderName, senderUUID, args)) {
                tpId(sender, player, args);
                check(sender, senderUUID, args);
            }
        }
        else if (command.getName().equalsIgnoreCase("claim")) {
            if (args.length == 0) {
                return false;
            }
            claim(sender, senderName, senderUUID, args);
        }
        else if (command.getName().equalsIgnoreCase("unclaim")) {
            if (args.length == 0) {
                return false;
            }
            int requestId = 0;

            try {
                requestId = Integer.parseInt(args[0]);

                if (sender instanceof Player) {
                    Request req = reqTable.getRequest(requestId);
                    if (req.getAssignedMod().equalsIgnoreCase(senderName) && req.getStatus() == RequestStatus.CLAIMED) {
                        req.setStatus(RequestStatus.OPEN);
                        req.setAssignedModUUID(null);
                        req.setAssignedMod(null);
                        reqTable.save(req);

                        environment.put("mod", senderName);
                        environment.put("request_id", String.valueOf(requestId));
                        messageMods(config.MOD__UNCLAIM);
                        environment.remove("mod");
                        environment.remove("request_id");
                    }
                }
            }
            catch (NumberFormatException ex) {
                sendMessage(sender, config.GENERAL__REQUEST_NUMBER);
            }
        }
        else if (command.getName().equalsIgnoreCase("done")) {
            if (args.length == 0) {
                return false;
            }

            int requestId = 0;

            try {
                requestId = Integer.parseInt(args[0]);

                String doneMessage = "";

                if (args.length > 1) {
                    StringBuilder doneMessageBuilder = new StringBuilder(args[1]);
                    for (int i = 2; i < args.length; i++) {
                        doneMessageBuilder.append(" ").append(args[i]);
                    }

                    doneMessage = doneMessageBuilder.toString();
                }

                Request req = reqTable.getRequest(requestId);

                if (req != null && req.getStatus() == RequestStatus.CLOSED) {
                    sendMessage(sender, config.MOD__ALREADY_CLOSED);
                }
                else {
                    if (sender.hasPermission("modreq.done") && req != null) {
                        environment.put("mod", senderName);
                        environment.put("request_id", String.valueOf(requestId));
                        messageMods(config.MOD__COMPLETED);
                        environment.remove("request_id");
                        environment.remove("mod");

                        if (doneMessage != null && doneMessage.length() != 0) {
                            environment.put("close_message", doneMessage);
                            messageMods(config.MOD__COMPLETED_MESSAGE);
                            environment.remove("close_message");
                        }
                    }
                    else {
						if (req.getPlayerUUID() != null && !req.getPlayerUUID().equals(senderUUID)) {
							req = null;
							sendMessage(sender, config.GENERAL__CLOSE_ERROR);
						}
                    }

                    if (req != null) {
                        req.setStatus(RequestStatus.CLOSED);
                        req.setCloseTime(System.currentTimeMillis());
                        req.setCloseMessage(doneMessage);
                        req.setAssignedModUUID(senderUUID);
                        req.setAssignedMod(senderName);

                        Player requestCreator = getServer().getPlayerExact(req.getPlayerName());
                        if (requestCreator != null) {
                            if (!requestCreator.getUniqueId().equals(senderUUID)) {
                                String message = "";
                                environment.put("close_message", doneMessage);
                                environment.put("mod", senderName);
                                environment.put("request_id", String.valueOf(req.getId()));
                                if (doneMessage != null && doneMessage.length() != 0) {
                                    sendMessage(requestCreator, config.GENERAL__COMPLETED_MESSAGE);
                                } else {
                                    sendMessage(requestCreator, config.GENERAL__COMPLETED);
                                }
                                environment.put("close_message", doneMessage);
                                environment.put("mod", senderName);
                                environment.put("request_id", String.valueOf(req.getId()));
                                requestCreator.sendMessage(ChatColor.GREEN + message);
                                environment.remove("close_message");
                                environment.remove("mod");
                            }
                            else {
                                if (!sender.hasPermission("modreq.done")) {
                                    environment.put("request_id", String.valueOf(requestId));
                                    messageMods(config.MOD__DELETED);
                                    environment.put("request_id", String.valueOf(requestId));
                                    sendMessage(sender, config.GENERAL__DELETED);
                                    environment.remove("request_id");
                                }
                            }
                            req.setCloseSeenByUser(true);
                        }
                        reqTable.save(req);
                    }
                }
            }
            catch (NumberFormatException ex) {
                sendMessage(sender, config.GENERAL__REQUEST_NUMBER);
            }
        }
        else if (command.getName().equalsIgnoreCase("reopen")) {
            if (args.length == 0) {
                return false;
            }
            int requestId = 0;

            try {
                requestId = Integer.parseInt(args[0]);

                if (sender instanceof Player) {
                    Request req = reqTable.getRequest(requestId);
                    if ((req.getAssignedModUUID().equals(senderUUID) && req.getStatus() == RequestStatus.CLAIMED) || req.getStatus() == RequestStatus.CLOSED) {
                        req.setStatus(RequestStatus.OPEN);
						req.setAssignedModUUID(null);
                        req.setAssignedMod(null);
                        req.setCloseSeenByUser(false);
                        reqTable.save(req);

                        environment.put("mod", sender.getName());
                        environment.put("request_id", String.valueOf(requestId));
                        messageMods(config.MOD__REOPENED);
                        environment.remove("mod");
                        environment.remove("request_id");
                    }
                }
            }
            catch (NumberFormatException ex) {
                sendMessage(sender, config.GENERAL__REQUEST_NUMBER);
            }
        } else if (command.getName().equalsIgnoreCase("elevate")) {
            if (args.length == 0) {
                return false;
            }
            int requestId = 0;

            try {
                requestId = Integer.parseInt(args[0]);

                Request req = reqTable.getRequest(requestId);
                if (req.getStatus() == RequestStatus.OPEN) {
                    req.setFlagForAdmin(true);
                    environment.put("request_id", String.valueOf(req.getId()));
                    messageMods(config.MOD__FLAGGED);
                    environment.remove("request_id");
                    reqTable.save(req);
                }
            }
            catch (NumberFormatException ex) {
                sendMessage(sender, config.GENERAL__REQUEST_NUMBER);
            }
        } else if ( command.getName().equalsIgnoreCase("mr-reset")) {
            try {
                resetDatabase();
                sendMessage(sender, config.MOD__RESET);
            } catch (Exception ex) {
                getLogger().log(Level.WARNING, "Failed to reset database", ex);
            }
        } else if ( command.getName().equalsIgnoreCase("mr-note")) {
            if (args.length < 3) {
                return false;
            }
            if (sender.hasPermission("modreq.mod")) {
                int reqId;

                try {
                    reqId = Integer.parseInt(args[1].trim());
                } catch (NumberFormatException e) {
                    return false;
                }

                Request request = reqTable.getRequest(reqId);
                if (request == null) {
                    return false;
                }

                if (args[0].equalsIgnoreCase("remove")) {

                    //kind of hacky but works
                    int idToRemove;

                    try {
                        idToRemove = Integer.parseInt(args[2]);
                    } catch (NumberFormatException e) {
                        return false;
                    }

                    List<Note> notes = noteTable.getRequestNotes(request);

                    Note noteToRemove = notes.get(idToRemove - 1);
                    if(noteToRemove == null) {
                        return false;
                    }

                    noteTable.remove(noteToRemove);
                    environment.put("request_id", Integer.toString(reqId));
                    sender.sendMessage(buildMessage(config.MOD__NOTE_REMOVED));
                    environment.remove("request_id");
                } else if (args[0].equalsIgnoreCase("add")) {

                    if(reqTable.getRequest(reqId) == null) {
                        return false;
                    }

                    StringBuilder noteBody = new StringBuilder(args[2]);
                    for (int i = 3; i < args.length; i++) {
                        noteBody.append(" ").append(args[i]);
                    }

                    Note note = new Note();
                    note.setNoteBody(noteBody.toString());
					note.setPlayerUUID(senderUUID);
                    note.setPlayer(senderName);
                    note.setRequestId(reqId);
                    noteTable.save(note);

                    environment.put("request_id", Integer.toString(reqId));
                    sender.sendMessage(buildMessage(config.MOD__NOTE_ADDED));
                    environment.remove("request_id");
                }
            }
        } else if ( command.getName().equalsIgnoreCase("mr-upgrade")) {
            if (sender.hasPermission("modreq.upgrade")) {
//				getLogger().log(Level.INFO, "Upgrading database");

//				try {
//					SqlUpdate update = getDatabase().createSqlUpdate("ALTER TABLE modreq_requests ADD COLUMN player_uuid VARCHAR(40)");
//					getDatabase().execute(update);
//					getLogger().log(Level.INFO, "Created player_uuid column on modreq_requests table");
//				} catch (PersistenceException e) {
//					// Column already exists
//				}
//
//				try {
//					SqlUpdate update = getDatabase().createSqlUpdate("ALTER TABLE modreq_requests ADD COLUMN assigned_mod_uuid VARCHAR(40)");
//					getDatabase().execute(update);
//					getLogger().log(Level.INFO, "Created assigned_mod_uuid column on modreq_requests table");
//				} catch (PersistenceException e) {
//					// Column already exists
//				}
//
//				try {
//					SqlUpdate update = getDatabase().createSqlUpdate("ALTER TABLE modreq_notes ADD COLUMN player_uuid VARCHAR(40)");
//					getDatabase().execute(update);
//					getLogger().log(Level.INFO, "Created player_uuid column on modreq_notes table");
//				} catch (PersistenceException e) {
//					// Column already exists
//				}
//
//				getDatabase().externalModification(name, includeElevated, includeElevated, includeElevated);

				BukkitScheduler scheduler = Bukkit.getServer().getScheduler();
				scheduler.scheduleSyncDelayedTask(this, new Runnable() {
					@Override
					public void run() {
						resetDatabase();
					}
				}, 0L);
			}
		}

        return true;
    }

    private void check(CommandSender sender, UUID senderUUID, String[] args) {
        // Setting page > 0 triggers a page listing.
        int page = 1;
        int requestId = 0;
        int totalRequests = 0;
        String searchTerm = null;
        UUID limitUUID = null;
        boolean showNotes = true;
        boolean includeElevated = sender.hasPermission("modreq.cleardb");

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equalsIgnoreCase("--admin") || arg.equalsIgnoreCase("-a")) {
                includeElevated = true;
            }
            else if (arg.startsWith("p:")) {
                page = Integer.parseInt(arg.substring(2));
            }
            else if (arg.equalsIgnoreCase("--page") || arg.equalsIgnoreCase("-p")) {
                if (i+1 > args.length) {
                    sendMessage(sender, config.GENERAL__PAGE_ERROR);
                    return;
                }
                else {
                    try {
                        page = Integer.parseInt(args[i+1]);
                        i++;
                    }
                    catch (NumberFormatException ex) {
                        sendMessage(sender, config.GENERAL__PAGE_ERROR);
                        return;
                    }
                }
            }
            else if (arg.equalsIgnoreCase("--search") || arg.equalsIgnoreCase("-s")) {
                if (i+1 < args.length) {
                    searchTerm = args[i+1];
                    i++;
                }
                else {
                    sendMessage(sender, config.GENERAL__SEARCH_ERROR);
                    return;
                }
            }
            else {
                try {
                    requestId = Integer.parseInt(arg);
                    page = 0;
                }
                catch (NumberFormatException ex) {
                    sendMessage(sender, config.GENERAL__REQUEST_NUMBER);
                    return;
                }
            }
        }

        if (!sender.hasPermission("modreq.check")) {
            if (sender instanceof Player) {
                limitUUID = senderUUID;
            }
            showNotes = false;
        }

        List<Request> requests = new ArrayList<Request>();

        if (page > 0) {
            if (limitUUID != null) {
                requests.addAll(reqTable.getUserRequests(limitUUID));
                totalRequests = requests.size();
            } else {
                requests.addAll(reqTable.getRequestPage(page - 1, 5, includeElevated, searchTerm, RequestStatus.OPEN, RequestStatus.CLAIMED));
                totalRequests = reqTable.getTotalRequest(includeElevated, searchTerm, RequestStatus.OPEN, RequestStatus.CLAIMED);
            }
        } else if (requestId > 0) {
            Request req = reqTable.getRequest(requestId);

            if (req != null) {
                totalRequests = 1;
                if (limitUUID != null && req.getPlayerUUID().equals(limitUUID)) {
                    requests.add(req);
                } else if (limitUUID == null) {
                    requests.add(req);
                } else {
                    totalRequests = 0;
                }
            } else {
                totalRequests = 0;
            }
        }

        if (totalRequests == 0) {
            if (limitUUID != null) {
                if (requestId > 0) {
                    sendMessage(sender, config.GENERAL__REQUEST_ERROR);
                }
                else {
                    sendMessage(sender, config.GENERAL__NO_REQUESTS);
                }
            }
            else {
                sendMessage(sender, config.MOD__NO_REQUESTS);
            }
        } else if (totalRequests == 1 && requestId > 0) {
            messageRequestToPlayer(sender, requests.get(0), showNotes);
        } else if (totalRequests > 0) {
            if (page > 1 && requests.isEmpty()) {
                sendMessage(sender, config.MOD__EMPTY_PAGE);
            } else {
                boolean showPage = true;
                if (limitUUID != null) {
                    showPage = false;
                }
                messageRequestListToPlayer(sender, requests, page, totalRequests, showPage);
            }
        } else {
            // there was an error.
        }
    }

    private void tpId(CommandSender sender, Player player, String[] args) {
        int requestId = 0;
        try {
            requestId = Integer.parseInt(args[0]);

            if (sender instanceof Player) {
                Request req = reqTable.getRequest(requestId);
                if (req != null) {
                    environment.put("request_id", String.valueOf(req.getId()));
                    sendMessage(player, config.MOD__TELEPORT);
                    Location loc = stringToLocation(req.getRequestLocation());
                    player.teleport(loc);
                }
                else {
                    sendMessage(sender, config.GENERAL__REQUEST_ERROR);
                }
            }
        }
        catch (NumberFormatException ex) {
            sendMessage(sender, config.GENERAL__REQUEST_NUMBER);
        }
    }

    /**
     * Do the work of the /claim command.
     *
     * @return true if the request was successfully claimed by the sender now
     *              or previously; false if closed or already claimed by some
     *              other player.
     */
    private boolean claim(CommandSender sender, String senderName, UUID senderUUID, String[] args) {
        int requestId = 0;
        try {
            requestId = Integer.parseInt(args[0]);

            if (sender instanceof Player) {
                Request req = reqTable.getRequest(requestId);

                if (req.getStatus() == RequestStatus.OPEN) {
                    req.setStatus(RequestStatus.CLAIMED);
					req.setAssignedModUUID(senderUUID);
                    req.setAssignedMod(senderName);
                    reqTable.save(req);

                    environment.put("mod", senderName);
                    environment.put("request_id", String.valueOf(requestId));
                    messageMods(config.MOD__REQUEST_TAKEN);
                    environment.remove("mod");
                    environment.remove("request_id");
                    return true;

                } else if (req.getStatus() == RequestStatus.CLOSED) {
                    sendMessage(sender, config.MOD__ALREADY_CLOSED);
                } else if (req.getStatus() == RequestStatus.CLAIMED) {
                    if (req.getAssignedModUUID().equals(senderUUID)) {
                        // Already claimed by command sender. Succeed.
                        return true;
                    } else {
                        sendMessage(sender, config.MOD__ALREADY_CLAIMED);
                    }
                }
            }
        }
        catch (NumberFormatException ex) {
            sendMessage(sender, config.GENERAL__REQUEST_NUMBER);
        }
        return false;
    }

    private Location stringToLocation(String requestLocation) {
        Location loc;
        double x, y, z;
        float pitch, yaw;
        String world;
        String[] split = requestLocation.split(",");
        world = split[0];
        x = Double.parseDouble(split[1]);
        y = Double.parseDouble(split[2]);
        z = Double.parseDouble(split[3]);
    if (split.length > 4) {
           yaw = Float.parseFloat(split[4]);
           pitch = Float.parseFloat(split[5]);
            loc = new Location(getServer().getWorld(world), x, y, z, yaw, pitch);
    } else {
           loc = new Location(getServer().getWorld(world), x, y, z);
        }
        return loc;
    }

    private String timestampToDateString(long timestamp) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(timestamp);
        SimpleDateFormat format = new SimpleDateFormat("MMM.d@k.m.s");
        return format.format(cal.getTime());
    }

    public String buildMessage(String inputMessage) {
        String message = inputMessage;

        for (Map.Entry<String, String> entry : environment.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key.equalsIgnoreCase("player")) {
                if (getServer().getPlayerExact(value) != null) {
                    value = config.COLOUR_ONLINE + value;
                }
                else {
                    value = config.COLOUR_OFFLINE + value;
                }
            }

            message = message.replace("{" + key + "}", value);
        }


        message = ChatColor.translateAlternateColorCodes('&', message);
        return message;
    }

    private void messageRequestToPlayer(CommandSender sender, Request req, boolean showNotes) {
        List<String> messages = new ArrayList<String>();
        Location loc = stringToLocation(req.getRequestLocation());
        String location = String.format("%s, %d, %d, %d", loc.getWorld().getName(), Math.round(loc.getX()), Math.round(loc.getY()), Math.round(loc.getZ()));

        environment.put("status", req.getStatus().toString());
        environment.put("request_id", String.valueOf(req.getId()));
        if (req.getStatus() == RequestStatus.CLAIMED) {
            environment.put("mod", req.getAssignedMod());
            messages.add(buildMessage(config.GENERAL__ITEM__HEADER_CLAIMED));
            environment.remove("mod");
        }
        else {
            messages.add(buildMessage(config.GENERAL__ITEM__HEADER_UNCLAIMED));
        }
        environment.remove("status");
        environment.remove("request_id");
        environment.put("player", req.getPlayerName());
        environment.put("time", timestampToDateString(req.getRequestTime()));
        environment.put("location", location);
        messages.add(buildMessage(config.GENERAL__ITEM__DETAILS));
        environment.remove("player");
        environment.remove("time");
        environment.remove("location");
        environment.put("request_message", req.getRequest());
        messages.add(buildMessage(config.GENERAL__ITEM__REQUEST));
        environment.remove("request_message");

        if (showNotes) {
            List<Note> notes = noteTable.getRequestNotes(req);

            int i = 1;
            for (Note note : notes) {
                environment.put("id", Integer.toString(i));
                environment.put("user", note.getPlayer());
                environment.put("message", note.getNoteBody());
                messages.add(buildMessage(config.GENERAL__ITEM__NOTE));

                i++;
            }
            environment.remove("id");
            environment.remove("player");
            environment.remove("message");
        }

        sender.sendMessage(messages.toArray(new String[1]));
    }

    private void messageRequestListToPlayer(CommandSender sender, List<Request> reqs, int page, int totalRequests, boolean showPage) {
        List<String> messages = new ArrayList<String>();

        environment.put("num_requests", String.valueOf(totalRequests));
        messages.add(buildMessage(config.GENERAL__LIST__HEADER));
        environment.remove("num_requests");
        for (Request r : reqs) {
            int noteCount = noteTable.getNoteCount(r);
            try {
                environment.put("request_id", String.valueOf(r.getId()));
                environment.put("note_count", noteCount>0?ChatColor.RED+" ["+Integer.toString(noteCount)+"]":"");
                environment.put("admin", (r.isFlagForAdmin()?(ChatColor.AQUA + " [ADMIN]"):""));
                environment.put("mod", (r.getStatus()==RequestStatus.CLAIMED?(r.getAssignedMod()):""));
                environment.put("status", (r.getStatus()!=RequestStatus.CLAIMED?(r.getStatus().toString()):""));
                environment.put("time", timestampToDateString(r.getRequestTime()));
                environment.put("player", r.getPlayerName());
                environment.put("request_message", r.getRequest());
                messages.add(buildMessage(config.GENERAL__LIST__ITEM));
                environment.remove("request_id");
                environment.remove("note_count");
                environment.remove("admin");
                environment.remove("mod");
                environment.remove("status");
                environment.remove("time");
                environment.remove("player");
                environment.remove("request_message");
            }
            catch (Exception ex) {
                ex.printStackTrace();
            }
        }

        if (showPage) {
            int numpages = (int)Math.ceil(totalRequests / config.MAX_REQUESTS.floatValue());
            environment.put("page", String.valueOf(page));
            environment.put("num_pages", String.valueOf(numpages));
            messages.add(buildMessage(config.GENERAL__LIST__FOOTER));
            environment.remove("page");
            environment.remove("num_pages");
        }

        sender.sendMessage(messages.toArray(new String[1]));
    }

    public void sendMessage(CommandSender sender, String message) {
        message = buildMessage(message);
        sender.sendMessage(message);
    }

    public void messageMods(String message) {
        String permission = "modreq.notice";
        message = buildMessage(message);
        this.getServer().broadcast(message, permission);

        Set<Permissible> subs = getServer().getPluginManager().getPermissionSubscriptions(permission);
        for (Player player : getServer().getOnlinePlayers()) {
            if (player.hasPermission(permission) && !subs.contains(player)) {
                player.sendMessage(message);
            }
        }
    }
}
