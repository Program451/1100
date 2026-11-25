"""FCM не влияет на сохранение тревоги и запасной опрос."""
import logging
import os

logger = logging.getLogger(__name__)

def send_alert(alert, tokens):
    if not tokens or not os.getenv('GOOGLE_APPLICATION_CREDENTIALS'):
        return
    try:
        import firebase_admin
        from firebase_admin import messaging
        try:
            firebase_admin.get_app()
        except ValueError:
            firebase_admin.initialize_app()
        data = {key: '' if value is None else str(value) for key, value in alert.items()}
        for token in tokens:
            try:
                messaging.send(messaging.Message(token=token, data=data))
            except Exception:
                logger.exception('Не удалось отправить push; тревога доступна через опрос')
    except Exception:
        logger.exception('Не удалось подготовить FCM; тревога доступна через опрос')
