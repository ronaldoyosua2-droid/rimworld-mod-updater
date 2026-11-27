using System;
using System.Globalization;
using System.Reflection;
using UnityEngine;
using Verse;

namespace ModSyncPlayTime
{
    /// <summary>
    /// Counts real seconds spent inside this save (AFK and pause included) and stores them in the save.
    /// Read by the ModSync Update app (tag: modsyncRealSeconds).
    /// </summary>
    public class GameComponent_ModSyncPlayTime : GameComponent
    {
        private double modsyncRealSeconds = -1;

        public GameComponent_ModSyncPlayTime(Game game)
        {
        }

        /// <summary>First time this save runs with the mod: start from RimWorld's own "active" play time.</summary>
        private void Seed()
        {
            if (modsyncRealSeconds >= 0) return;
            double active = 0;
            try
            {
                FieldInfo f = typeof(GameInfo).GetField("realPlayTimeInteracting",
                    BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic);
                if (f != null && Find.GameInfo != null) active = Convert.ToDouble(f.GetValue(Find.GameInfo));
            }
            catch (Exception)
            {
            }
            modsyncRealSeconds = active < 0 ? 0 : active;
        }

        public override void LoadedGame() => Seed();

        public override void StartedNewGame() => Seed();

        public override void GameComponentUpdate()
        {
            if (modsyncRealSeconds < 0) Seed();
            float dt = Time.unscaledDeltaTime;
            // big jumps (app was in the background, phone asleep, long load) are not play time
            if (dt > 0f && dt < 5f) modsyncRealSeconds += dt;
        }

        public override void ExposeData()
        {
            base.ExposeData();
            // stored as text (invariant culture) so it loads the same on every RimWorld version / phone language
            string text = modsyncRealSeconds < 0 ? null : modsyncRealSeconds.ToString("0.###", CultureInfo.InvariantCulture);
            Scribe_Values.Look(ref text, "modsyncRealSeconds");
            if (Scribe.mode == LoadSaveMode.LoadingVars)
            {
                modsyncRealSeconds = text != null && double.TryParse(text, NumberStyles.Float, CultureInfo.InvariantCulture, out double v) ? v : -1;
            }
        }
    }
}
