/*
 * Copyright (C) 2012-2013 Hans Hardmeier <hanshardmeier@gmail.com>
 * Copyright (C) 2012-2013 Andrin Jenal
 * Copyright (C) 2012-2013 Beat Küng <beat-kueng@gmx.net>
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; version 3 of the License.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 */

package com.sapos_aplastados.game.clash_of_balls.game;


import java.util.Map;

import org.jbox2d.common.Vec2;

import android.content.Context;
import android.opengl.GLES20;
import android.util.Log;

import com.sapos_aplastados.game.clash_of_balls.Font2D;
import com.sapos_aplastados.game.clash_of_balls.FontNumbers;
import com.sapos_aplastados.game.clash_of_balls.GameLevel;
import com.sapos_aplastados.game.clash_of_balls.GameSettings;
import com.sapos_aplastados.game.clash_of_balls.TextureManager;
import com.sapos_aplastados.game.clash_of_balls.UIBase;
import com.sapos_aplastados.game.clash_of_balls.UIHandler;
import com.sapos_aplastados.game.clash_of_balls.VertexBufferFloat;
import com.sapos_aplastados.game.clash_of_balls.Font2D.Font2DSettings;
import com.sapos_aplastados.game.clash_of_balls.Font2D.TextAlign;
import com.sapos_aplastados.game.clash_of_balls.UIHandler.UIChange;
import com.sapos_aplastados.game.clash_of_balls.game.event.Event;
import com.sapos_aplastados.game.clash_of_balls.game.event.EventImpact;
import com.sapos_aplastados.game.clash_of_balls.game.event.EventGameInfo.PlayerInfo;
import com.sapos_aplastados.game.clash_of_balls.menu.PopupBase;
import com.sapos_aplastados.game.clash_of_balls.menu.PopupGameStart;
import com.sapos_aplastados.game.clash_of_balls.menu.PopupMsg;
import com.sapos_aplastados.game.clash_of_balls.network.NetworkClient;
import com.sapos_aplastados.game.clash_of_balls.network.Networking;
import com.sapos_aplastados.game.clash_of_balls.network.Networking.AllJoynError;
import com.sapos_aplastados.game.clash_of_balls.network.Networking.AllJoynErrorData;
import com.sapos_aplastados.game.clash_of_balls.network.Networking.ConnectedClient;


public class Game extends GameBase implements UIBase {
	private static final String TAG_GAME = "Game";
	
	private SensorThread m_sensor_thread;
	private GameView m_view;
	
	private UIHandler.UIChange m_ui_change;
	private PopupBase m_error_popup = null;
	private Context m_activity_context;
	private Font2DSettings m_font_settings;
	
	private NetworkClient m_network_client;
	
	private float m_calibration_timeout=0.f; //[sec]
	
	
	protected static final VertexBufferFloat m_default_position_data=new VertexBufferFloat(
			VertexBufferFloat.sprite_position_data, 3); //for all game objects (for drawing)
	protected static final float m_default_color[]=RenderHelper.color_white;
	
	
	public Game(Context c, GameSettings s, TextureManager texture_manager, 
			NetworkClient network_client, Font2DSettings font_settings) {
		super(false, s, texture_manager);
		
		m_sensor_thread=new SensorThread(c);
		m_activity_context = c;
		m_font_settings = font_settings;
		m_sensor_thread.startThread();
		m_network_client = network_client;
		m_ui_change = UIHandler.UIChange.NO_CHANGE;
		initOverlayTimeFonts();
		m_event_pool = network_client.m_event_pool;
	}
	
	private void initOverlayTimeFonts() {
		Font2DSettings font_settings = new Font2DSettings(m_font_settings.m_typeface
				, TextAlign.LEFT, m_font_settings.m_color);
		final float texture_height = ((float)m_settings.m_screen_height)*
			GamePlayer.overlay_item_height;
		m_overlay_font_numbers = new FontNumbers(m_texture_manager, font_settings
				, new Vector(texture_height*3.f, texture_height));
		//init the fonts (for caching)
		for(int i=(int)GameItem.item_effect_duration + 1; i>0; --i)
			m_overlay_font_numbers.getFont(i);
	}
	
	public void initGame(GameLevel level) {
		super.initGame(level);
		
		//after this call (and initPlayers) we should have wait_to_start_game 
		//seconds until the game starts
		
		//view: save & restore scaling if it exists
		float scaling = -1.f;
		float old_width=0.f, old_height=0.f;
		if(m_view != null) {
			scaling = m_view.getZoomToTileSize();
			old_width = m_view.levelWidth();
			old_height = m_view.levelHeight();
		}
		m_view = new GameView(m_settings.m_screen_width, m_settings.m_screen_height, 
				null, (float)level.width, (float)level.height);
		if(scaling > 0.f && old_width==m_view.levelWidth() && old_height==m_view.levelHeight())
			m_view.setZoomToTileSize(scaling);
		
	}
	
	public void initPlayers(PlayerInfo[] players) {
		//initialize network client id (must be done before super.initPlayers !)
		for(int i=0; i<players.length; ++i) {
			for(int k=0; k<m_network_client.getConnectedClientCount(); ++k) {
				ConnectedClient client=m_network_client.getConnectedClient(k);
				if(client!=null && client.unique_id.equals(players[i].unique_name)) {
					client.id = players[i].id;
				}
			}
		}
		
		super.initPlayers(players);
		
		String own_unique_name = m_network_client.getOwnUniqueName();
		if(own_unique_name != null) {
			for(int i=0; i<players.length; ++i) {
				if(own_unique_name.equals(players[i].unique_name)) {
					m_own_player = (GamePlayer)m_game_objects.get(players[i].id);
					
					Log.d(TAG_GAME, "we got our player at x="+m_own_player.pos().x
							+", y="+m_own_player.pos().y);
				}
			}
		}
		
		if(m_own_player == null) {
			Log.e(TAG_GAME, "could not find own player in players list! This is very bad!");
		}
		
		m_view.setObjectToTrack(m_own_player);
		
		//start calibration
		m_calibration_timeout = (float)wait_to_start_game - 1.f;
		
		//show game start popup
		if(m_error_popup == null) {
			m_settings.popup_menu = new PopupGameStart(m_activity_context
					, m_texture_manager, m_settings.m_screen_width, m_settings.m_screen_height
					, (float)wait_to_start_game, RenderHelper.getColor(m_own_player.color())
					, m_font_settings.m_typeface, m_world);
			m_ui_change = UIChange.POPUP_SHOW;
		}
	}
	
	public void onDestroy() {
		if(m_sensor_thread!=null) m_sensor_thread.stopThread();
		m_sensor_thread = null;
	}

	public void onTouchEvent(float x, float y, int event) {
		// that's not used in the game
	}
	
	private boolean m_bReceived_events = false; //send new sensor data if true
	private float m_time_since_last_data_receive=0.f; //[sec]
	private float m_last_rtt; //[sec] round time trip in sec
	private static final float network_receive_timeout = 4.f; //[sec]
	
	private Vector m_sensor_vector=new Vector();
	private Vector m_last_sensor_update=new Vector();

	public void move(float dsec) {
		if(m_error_popup != null) {
			//check for button pressed
			if(m_error_popup.UIChange() == UIChange.POPUP_RESULT_BUTTON1) {
				gameAbort();
				m_error_popup = null;
			}
		} else {
			//calibration
			if(m_calibration_timeout > 0.f) {
				m_calibration_timeout -= dsec;
				if(m_calibration_timeout <= 0.f) {
					m_sensor_thread.calibrate();
				}
			}

			if(m_bIs_game_running) {

				//get sensor values & send to server
				m_sensor_thread.getCurrentVector(m_sensor_vector);
				if(m_bReceived_events) {
					m_network_client.sensorUpdate(m_sensor_vector);
					m_last_sensor_update.set(m_sensor_vector);
				}
				
				handleNetworkError(m_network_client.getNetworkError());

				m_network_client.handleReceive();
				boolean has_network_events = m_network_client.hasEvents();
				m_bReceived_events = has_network_events;
				if(has_network_events) {
					//undo events: simply discard them
					Event e;
					while((e=m_events.poll()) != null) {
						m_event_pool.recycle(e);
					}
					
					m_last_rtt = m_time_since_last_data_receive;
					m_time_since_last_data_receive = 0.f;
					
					//we have a synchronization update from the server
					//we assume that all predicted events (game moves) 
					//can simply be overwritten by the server update

					//Log.v(TAG_GAME, "Network incoming: RTT="+m_last_rtt);

					generate_events = false;
					
					if(!GameSettings.client_prediction) {
						super.move(m_last_rtt);
					}
					
					//apply the updates from the server
					applyIncomingEvents();
					
					removeDeadObjects();
				}
				
				//do a predicted move
				generate_events = true;
				
				/* 
				 * Undo Predicted Events
				 * ---------------------
				 * for the future if we want to be able to undo predicted events:
				 * - add member boolean m_is_predicting
				 * - use a separate Event queue & check in addEvent if m_is_predicting
				 * - add an undo method in Event class
				 * - before applying network events: undo all Events in the queue
				 * - add here:
					generate_events = true;
					m_is_predicting = true;
				 */
				
				//move the client stuff like animation
				//this can be done in any case, because this does not to be
				//strictly synchronized with the server
				moveClient(dsec);
				
				if(GameSettings.client_prediction) { 
					//even if we had network updates, we should move one step
					//to apply the new positions smoothly
					super.move(dsec);
				}
				
				
				m_game_field.move(dsec);


				m_view.move(dsec);
				m_game_field.move(dsec);
				
				//we expect the sensor update to be received by the server
				//1/2 RTT after sending, so we try to apply the sensor update
				//at the same time as the server
				if(m_time_since_last_data_receive < m_last_rtt/2.f
						&& m_time_since_last_data_receive+dsec >= m_last_rtt/2.f)
					m_own_player.applySensorVector(m_last_sensor_update);
				
				
				//check for receive timeout
				if((m_time_since_last_data_receive+=dsec) > network_receive_timeout) {
					AllJoynErrorData error = new AllJoynErrorData();
					error.error_string = "";
					error.error = AllJoynError.RECEIVE_TIMEOUT;
					handleNetworkError(error);
				}

			} else {
				m_network_client.handleReceive();
				applyIncomingEvents();
			}
		}
	}
	
	private void applyIncomingEvents() {
		Event e;
		while((e=m_network_client.getNextEvent()) != null) {
			e.apply(this);
			m_event_pool.recycle(e);
		}
	}
	
	private void handleNetworkError(AllJoynErrorData data) {
		if(data != null) {
			//this is bad: here it's very difficult to recover, so we 
			//show a message to the user and abort the game
			
			switch(data.error) {
			case CONNECT_ERROR:
			case JOIN_SESSION_ERROR:
			case SEND_ERROR:
			case BUS_EXCEPTION:
			case RECEIVE_TIMEOUT:
				m_settings.popup_menu = m_error_popup = new PopupMsg(m_activity_context 
						, m_texture_manager, m_settings.m_screen_width
						, m_settings.m_screen_height 
						, m_font_settings.m_typeface, m_font_settings.m_color
						, "Error", Networking.getErrorMsgMultiline(data.error), "Ok");
				m_ui_change = UIChange.POPUP_SHOW;
			}
			
		}
	}

	public void draw(RenderHelper renderer) {
		
		if(m_view != null && m_game_field != null) {
			applyDefaultPosAndColor(renderer);
			
			m_view.applyView(renderer);

			m_game_field.draw(renderer);
			for (Map.Entry<Short, DynamicGameObject> entry : m_game_objects.entrySet()) {
				entry.getValue().drawBackground(renderer);
			}
			for (Map.Entry<Short, DynamicGameObject> entry : m_game_objects.entrySet()) {
				entry.getValue().draw(renderer);
			}

			m_view.resetView(renderer);
			
			if(m_own_player != null)
				m_own_player.drawOverlay(renderer);
		}
	}
	
	//call this before drawing StaticGameObjects or when the shader changes
	public static void applyDefaultPosAndColor(RenderHelper renderer) {
		//position
		int position_handle = renderer.shaderManager().a_Position_handle;
		if(position_handle != -1)
			m_default_position_data.apply(position_handle);
		
        // color
		int color_handle = renderer.shaderManager().u_Color_handle;
		if(color_handle != -1)
			GLES20.glUniform4fv(color_handle, 1, m_default_color, 0);
	}
	
	public void gameStartNow() {
		super.gameStartNow();
		m_bReceived_events = true;
		m_time_since_last_data_receive = 0.f;
		m_sensor_thread.stopCalibrate();
		m_ui_change = UIChange.POPUP_HIDE;
	}
	public void gameEnd() {
		super.gameEnd();
		m_ui_change = UIHandler.UIChange.GAME_ROUND_END;
	}
	
	public UIHandler.UIChange UIChange() {
		UIHandler.UIChange ret = m_ui_change;
		m_ui_change = UIChange.NO_CHANGE;
		return ret;
	}

	public void onActivate() {
		// ignore
	}

	public void onDeactivate() {
		gameEnd();
		m_ui_change = UIHandler.UIChange.NO_CHANGE;
	}
	
	public String getUniqueNameFromPlayerId(short player_id) {
		throw new RuntimeException("getUniqueNameFromPlayerId should not be called inside Game object");
	}
	
	private void gameAbort() {
		m_bIs_game_running = false;
		m_ui_change = UIChange.GAME_ABORT;
	}
	
	public void onBackButtonPressed() {
		//ok the user wants it this way: abort the game
		gameAbort();
	}
}
