package com.example.smartweed;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import com.example.smartweed.databinding.FragmentHomeBinding;

public class HomeFragment extends Fragment {

    private FragmentHomeBinding binding;

    // Debounce gegen Doppelklicks
    private static final long MIN_CLICK_INTERVAL_MS = 600L;
    private long lastClickTs = 0L;

    // Interpolatoren für Premium-Animationen
    private final DecelerateInterpolator easeOut = new DecelerateInterpolator(1.5f);
    private final OvershootInterpolator overshoot = new OvershootInterpolator(1.2f);
    private final AccelerateDecelerateInterpolator smooth = new AccelerateDecelerateInterpolator();

    // Floating orb animators
    private ValueAnimator orbAnimator1;
    private ValueAnimator orbAnimator2;
    private ValueAnimator orbAnimator3;

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState
    ) {
        binding = FragmentHomeBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Accessibility
        binding.titleHeader.setContentDescription(getString(R.string.home_title));
        binding.subtitle.setContentDescription(getString(R.string.home_subtitle));
        binding.statusContainer.setContentDescription(getString(R.string.status_ready));
        binding.footerInfo.setContentDescription(getString(R.string.footer_info));

        // Premium-Schatten für Buttons
        float elevPrimary = getResources().getDisplayMetrics().density * 12f;
        float elevSecondary = getResources().getDisplayMetrics().density * 6f;
        ViewCompat.setElevation(binding.buttonFirst, elevPrimary);
        ViewCompat.setElevation(binding.buttonToSessions, elevSecondary);
        ViewCompat.setElevation(binding.buttonToTrash, elevSecondary);

        // Click Handler
        binding.buttonFirst.setOnClickListener(v -> navigateDebounced(v, R.id.action_HomeFragment_to_CameraFragment));
        binding.buttonToSessions.setOnClickListener(v -> navigateDebounced(v, R.id.action_HomeFragment_to_sessionsFragment));
        binding.buttonToTrash.setOnClickListener(v -> navigateDebounced(v, R.id.action_HomeFragment_to_trashFragment));

        // Premium Press Feedback mit Glow-Effekt
        setPremiumPressFeedback(binding.buttonFirst);
        setPremiumPressFeedback(binding.buttonToSessions);
        setPremiumPressFeedback(binding.buttonToTrash);

        // Startzustand für Intro-Animation
        prepForIntro(binding.headerCard, -30f, 0f);
        prepForIntro(binding.buttonFirst, 40f, 0.95f);
        prepForIntro(binding.buttonToSessions, 50f, 0.95f);
        prepForIntro(binding.buttonToTrash, 50f, 0.95f);
        prepForIntro(binding.infoCard, 35f, 0f);

        // Logo Container Animation vorbereiten
        View logoContainer = binding.headerCard.findViewById(R.id.logoContainer);
        if (logoContainer != null) {
            logoContainer.setScaleX(0.5f);
            logoContainer.setScaleY(0.5f);
            logoContainer.setAlpha(0f);
            logoContainer.setRotation(-15f);
        }

        view.post(this::runPremiumIntroAnimations);

        // Premium Parallax Effekt
        binding.rootScroll.setOnScrollChangeListener(
                new NestedScrollView.OnScrollChangeListener() {
                    @Override
                    public void onScrollChange(@NonNull NestedScrollView v,
                                               int scrollX, int scrollY, int oldScrollX, int oldScrollY) {
                        float parallax = scrollY * 0.35f;
                        binding.headerCard.setTranslationY(-parallax);

                        float alpha = 1f - Math.min(1f,
                                (float) scrollY / (binding.headerCard.getHeight() + 1f));
                        binding.headerCard.setAlpha(Math.max(0.7f, alpha));

                        // Subtle scale effect on scroll
                        float scale = 1f - Math.min(0.05f, scrollY / 2000f);
                        binding.headerCard.setScaleX(scale);
                        binding.headerCard.setScaleY(scale);
                    }
                });

        // Premium Breathing Animation für Status
        startPremiumBreathing(binding.statusContainer);

        // Floating Orbs Animation starten
        startFloatingOrbsAnimation(view);
    }

    private void navigateDebounced(@NonNull View v, int actionId) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastClickTs < MIN_CLICK_INTERVAL_MS) return;
        lastClickTs = now;

        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);

        // Premium exit animation
        v.animate()
                .scaleX(0.95f)
                .scaleY(0.95f)
                .alpha(0.8f)
                .setDuration(100)
                .setInterpolator(easeOut)
                .start();

        v.setEnabled(false);
        try {
            NavHostFragment.findNavController(this).navigate(actionId);
        } finally {
            v.postDelayed(() -> {
                v.setEnabled(true);
                v.setScaleX(1f);
                v.setScaleY(1f);
                v.setAlpha(1f);
            }, MIN_CLICK_INTERVAL_MS);
        }
    }

    private void setPremiumPressFeedback(@NonNull View target) {
        target.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    // Premium press animation mit Glow-Effekt
                    v.animate()
                            .scaleX(0.96f)
                            .scaleY(0.96f)
                            .setDuration(100)
                            .setInterpolator(easeOut)
                            .start();
                    // Subtle elevation increase
                    ViewCompat.animate(v)
                            .translationZ(dp(4))
                            .setDuration(100)
                            .start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    // Bounce-back mit Overshoot
                    v.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(200)
                            .setInterpolator(overshoot)
                            .start();
                    ViewCompat.animate(v)
                            .translationZ(0)
                            .setDuration(200)
                            .start();
                    break;
            }
            return false;
        });
    }

    private void prepForIntro(@NonNull View v, float translateYdp, float initialScale) {
        float dy = dp(translateYdp);
        v.setAlpha(0f);
        v.setTranslationY(dy);
        if (initialScale > 0) {
            v.setScaleX(initialScale);
            v.setScaleY(initialScale);
        }
    }

    private void runPremiumIntroAnimations() {
        final View card = binding.headerCard;
        int cx = (card.getLeft() + card.getRight()) / 2;
        int cy = (card.getTop() + card.getBottom()) / 2;
        float finalRadius = (float) Math.hypot(card.getWidth(), card.getHeight());

        // Premium Circular Reveal mit längerer Dauer
        card.setAlpha(1f);
        Animator reveal = ViewAnimationUtils.createCircularReveal(card, cx, cy, dp(0.5f), finalRadius);
        reveal.setDuration(700);
        reveal.setInterpolator(easeOut);
        reveal.start();

        // Logo Animation (wenn vorhanden)
        View logoContainer = binding.headerCard.findViewById(R.id.logoContainer);
        if (logoContainer != null) {
            logoContainer.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .rotation(0f)
                    .setStartDelay(200)
                    .setDuration(600)
                    .setInterpolator(overshoot)
                    .start();
        }

        // Staggered Button Animations mit Scale
        long base = 150;
        animateInPremium(binding.buttonFirst, base + 200, true);
        animateInPremium(binding.buttonToSessions, base + 300, true);
        animateInPremium(binding.buttonToTrash, base + 380, true);
        animateInPremium(binding.infoCard, base + 440, false);
    }

    private void animateInPremium(@NonNull View v, long startDelay, boolean withScale) {
        AnimatorSet animSet = new AnimatorSet();

        ObjectAnimator alpha = ObjectAnimator.ofFloat(v, "alpha", 0f, 1f);
        ObjectAnimator translateY = ObjectAnimator.ofFloat(v, "translationY", v.getTranslationY(), 0f);

        if (withScale) {
            ObjectAnimator scaleX = ObjectAnimator.ofFloat(v, "scaleX", 0.95f, 1f);
            ObjectAnimator scaleY = ObjectAnimator.ofFloat(v, "scaleY", 0.95f, 1f);
            animSet.playTogether(alpha, translateY, scaleX, scaleY);
        } else {
            animSet.playTogether(alpha, translateY);
        }

        animSet.setStartDelay(startDelay);
        animSet.setDuration(450);
        animSet.setInterpolator(easeOut);
        animSet.start();

        // Subtle bounce at end
        v.postDelayed(() -> {
            v.animate()
                    .translationY(dp(-3))
                    .setDuration(100)
                    .setInterpolator(smooth)
                    .withEndAction(() ->
                            v.animate()
                                    .translationY(0f)
                                    .setDuration(150)
                                    .setInterpolator(overshoot)
                                    .start()
                    )
                    .start();
        }, startDelay + 450);
    }

    private void startPremiumBreathing(@NonNull View chip) {
        ValueAnimator va = ValueAnimator.ofFloat(1f, 1.06f);
        va.setDuration(2000);
        va.setRepeatCount(ValueAnimator.INFINITE);
        va.setRepeatMode(ValueAnimator.REVERSE);
        va.setInterpolator(smooth);
        va.addUpdateListener(a -> {
            float s = (float) a.getAnimatedValue();
            chip.setScaleX(s);
            chip.setScaleY(s);
            // Subtle alpha pulse
            chip.setAlpha(0.9f + (s - 1f) * 1.5f);
        });
        va.start();
        chip.setTag(va);
    }

    private void startFloatingOrbsAnimation(@NonNull View rootView) {
        // Find orbs
        View orb1 = rootView.findViewById(R.id.floatingOrb1);
        View orb2 = rootView.findViewById(R.id.floatingOrb2);
        View orb3 = rootView.findViewById(R.id.floatingOrb3);

        // Animate orb 1 - slow vertical float
        if (orb1 != null) {
            orbAnimator1 = ValueAnimator.ofFloat(0f, 1f);
            orbAnimator1.setDuration(6000);
            orbAnimator1.setRepeatCount(ValueAnimator.INFINITE);
            orbAnimator1.setRepeatMode(ValueAnimator.REVERSE);
            orbAnimator1.setInterpolator(smooth);
            orbAnimator1.addUpdateListener(a -> {
                float progress = (float) a.getAnimatedValue();
                orb1.setTranslationY(dp(15) * progress);
                orb1.setTranslationX(dp(8) * (float) Math.sin(progress * Math.PI));
                orb1.setAlpha(0.4f + 0.2f * progress);
            });
            orbAnimator1.start();
        }

        // Animate orb 2 - different timing
        if (orb2 != null) {
            orbAnimator2 = ValueAnimator.ofFloat(0f, 1f);
            orbAnimator2.setDuration(5000);
            orbAnimator2.setRepeatCount(ValueAnimator.INFINITE);
            orbAnimator2.setRepeatMode(ValueAnimator.REVERSE);
            orbAnimator2.setInterpolator(smooth);
            orbAnimator2.addUpdateListener(a -> {
                float progress = (float) a.getAnimatedValue();
                orb2.setTranslationY(dp(-12) * progress);
                orb2.setTranslationX(dp(-6) * (float) Math.sin(progress * Math.PI));
                orb2.setAlpha(0.35f + 0.15f * progress);
            });
            orbAnimator2.setStartDelay(1500);
            orbAnimator2.start();
        }

        // Animate orb 3 - subtle scale pulse
        if (orb3 != null) {
            orbAnimator3 = ValueAnimator.ofFloat(0f, 1f);
            orbAnimator3.setDuration(4000);
            orbAnimator3.setRepeatCount(ValueAnimator.INFINITE);
            orbAnimator3.setRepeatMode(ValueAnimator.REVERSE);
            orbAnimator3.setInterpolator(smooth);
            orbAnimator3.addUpdateListener(a -> {
                float progress = (float) a.getAnimatedValue();
                float scale = 1f + 0.15f * progress;
                orb3.setScaleX(scale);
                orb3.setScaleY(scale);
                orb3.setAlpha(0.3f + 0.1f * progress);
            });
            orbAnimator3.setStartDelay(800);
            orbAnimator3.start();
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        // Cancel breathing animation
        Object tag = binding != null ? binding.statusContainer.getTag() : null;
        if (tag instanceof ValueAnimator) {
            ((ValueAnimator) tag).cancel();
        }

        // Cancel orb animations
        if (orbAnimator1 != null) {
            orbAnimator1.cancel();
            orbAnimator1 = null;
        }
        if (orbAnimator2 != null) {
            orbAnimator2.cancel();
            orbAnimator2 = null;
        }
        if (orbAnimator3 != null) {
            orbAnimator3.cancel();
            orbAnimator3 = null;
        }

        binding = null;
    }
}
